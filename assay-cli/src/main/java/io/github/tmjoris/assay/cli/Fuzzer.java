package io.github.tmjoris.assay.cli;

import io.github.tmjoris.assay.cli.EngineProcess.Reply;
import io.github.tmjoris.assay.cli.Oracle.Kind;
import io.github.tmjoris.assay.cli.Oracle.Verdict;
import io.github.tmjoris.assay.compare.ResultSet;
import io.github.tmjoris.assay.gen.DataGen;
import io.github.tmjoris.assay.gen.QueryGen;
import io.github.tmjoris.assay.run.PlanStats;
import io.github.tmjoris.assay.shrink.Shrinker;
import io.github.tmjoris.assay.sql.Query;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates queries, runs them on every engine, and turns each difference into a small report. */
final class Fuzzer {

    record Config(
            long seed,
            int queries,
            int rows,
            List<String> engines,
            Path out,
            Path classpaths,
            String timeZone,
            int shrinkBudget,
            int maxShrinks) {}

    /** Counters for one engine under test. */
    static final class EngineStats {
        int compared;
        int anyNative;
        int fullyNative;
        long millis;
        final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
    }

    private static final Pattern TABLE = Pattern.compile("assay\\.d\\.(t\\d)");
    private static final Pattern REASON = Pattern.compile("Reason: (.*)");
    private static final Duration SETUP_TIMEOUT = Duration.ofMinutes(3);

    /** A difference must recur this many more times before it is treated as a finding. */
    private static final int RECHECKS = 2;

    private final Config config;
    private final Map<String, EngineStats> stats = new LinkedHashMap<>();
    private final Map<String, Integer> findings = new HashMap<>();
    private DataGen data;
    private EngineProcess reference;
    private Oracle oracle;
    private int unusable;
    private int shrinks;
    private int flaky;
    private int attempt;

    Fuzzer(Config config) {
        this.config = config;
    }

    void run() throws IOException {
        Path warehouse = config.out().resolve("warehouse").toAbsolutePath();
        Path logs = Files.createDirectories(config.out().resolve("logs"));
        Path scratch = Files.createDirectories(config.out().resolve("scratch"));
        Files.createDirectories(config.out().resolve("findings"));
        long started = System.nanoTime();

        reference = open("spark", warehouse, logs);
        data = new DataGen(config.seed(), config.rows());
        for (String sql : data.setup()) {
            Reply r = reference.exec(sql, SETUP_TIMEOUT);
            if (!r.ok()) {
                throw new IOException("setup failed: " + r.error() + "\n" + sql);
            }
        }
        oracle = new Oracle(reference, scratch);

        List<EngineProcess> tested = new ArrayList<>();
        try {
            for (String engine : config.engines()) {
                tested.add(open(engine, warehouse, logs));
                stats.put(engine, new EngineStats());
            }
            QueryGen gen = new QueryGen(config.seed());
            for (int i = 0; i < config.queries(); i++) {
                Query q = gen.next();
                for (EngineProcess engine : tested) {
                    Verdict v = oracle.check(engine, q.sql(), q.ordered());
                    if (!v.usable()) {
                        unusable++;
                        break;
                    }
                    record(engine, v);
                    if (v.differs()) {
                        handleDifference(engine, q, v, i);
                    }
                }
                if ((i + 1) % 25 == 0) {
                    System.out.printf("%d/%d queries, %d unusable, %d distinct findings%n", i + 1, config.queries(), unusable, findings.size());
                }
            }
        } finally {
            tested.forEach(EngineProcess::close);
            reference.close();
        }
        Summary.write(config, stats, findings, unusable, flaky, shrinks, (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private EngineProcess open(String engine, Path warehouse, Path logs) throws IOException {
        return new EngineProcess(engine, config.classpaths(), warehouse.toString(), config.timeZone(), logs.resolve(engine + ".log"));
    }

    private void record(EngineProcess engine, Verdict v) {
        EngineStats s = stats.get(engine.engine);
        s.compared++;
        if (v.kind() != null) {
            s.kinds.merge(v.kind(), 1, Integer::sum);
        }
        if (v.tested() != null && v.tested().ok()) {
            s.millis += v.tested().millis();
            PlanStats plan = PlanStats.parse(v.tested().plan());
            s.anyNative += plan.anyNative() ? 1 : 0;
            s.fullyNative += plan.fullyNative() ? 1 : 0;
        }
    }

    private void handleDifference(EngineProcess engine, Query original, Verdict first, int index) throws IOException {
        for (int i = 0; i < RECHECKS; i++) {
            if (oracle.check(engine, original.sql(), original.ordered()).kind() != first.kind()) {
                flaky++;
                return;
            }
        }
        String errorSignature = errorSignature(engine, first);
        if (errorSignature != null && findings.containsKey(errorSignature)) {
            findings.merge(errorSignature, 1, Integer::sum);
            return;
        }
        if (shrinks >= config.maxShrinks()) {
            Files.writeString(
                    config.out().resolve("unshrunk.txt"),
                    engine.engine + "\t" + first.kind() + "\t" + index + "\t" + original.sql() + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            return;
        }
        shrinks++;
        Shrinker.Result small =
                new Shrinker(config.shrinkBudget())
                        .shrink(original, q -> oracle.check(engine, q.sql(), q.ordered()).kind() == first.kind());
        Query q = small.query();
        String signature = errorSignature != null ? errorSignature : engine.engine + ":" + first.kind() + ":" + normalize(q.sql());
        int seen = findings.merge(signature, 1, Integer::sum);
        if (seen > 1) {
            return;
        }

        RowShrink rows = shrinkRows(engine, q, first.kind());
        Verdict finalVerdict = rows.verdict();
        String id = String.format("%04d-%s-%s", findings.size(), engine.engine, first.kind().name().toLowerCase());
        Path dir = Files.createDirectories(config.out().resolve("findings").resolve(id));
        String repro = repro(rows.sql(), rows.kept());
        Files.writeString(dir.resolve("repro.sql"), repro);
        keepResult(oracle.resultFile("reference"), dir.resolve("reference.arrow"));
        keepResult(oracle.resultFile(engine.engine), dir.resolve(engine.engine + ".arrow"));
        Files.writeString(dir.resolve("report.md"), report(engine, first, original, rows, finalVerdict, index, dir));
        System.out.printf("finding %s after %d shrink tests: %s%n", id, small.tests(), q.sql());
    }

    /** A crashed or failed engine leaves no result file, so there may be nothing to keep. */
    private static void keepResult(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record RowShrink(String sql, Map<String, List<Long>> kept, Verdict verdict) {}

    /**
     * Copies the query's tables into a scratch namespace row by row until the smallest failing data
     * is left, then rechecks it from a clean copy so the report never claims an unreproducible
     * result.
     */
    private RowShrink shrinkRows(EngineProcess engine, Query q, Kind kind) throws IOException {
        List<String> tables = tablesIn(q.sql());
        Map<String, List<Long>> kept = new LinkedHashMap<>();
        for (String t : tables) {
            kept.put(t, new ArrayList<>(data.rows(t).keySet()));
        }
        // A copy has one simple file layout. If the failure needs the original layout, shrinking
        // copies can only waste the budget, so keep the full data and let the report say so.
        if (!reproduces(engine, q, kept, kind)) {
            return new RowShrink(q.sql(), kept, oracle.check(engine, q.sql(), q.ordered()));
        }
        DataShrinker shrinker = new DataShrinker(config.shrinkBudget() / 2);
        for (String t : tables) {
            List<Long> smaller =
                    shrinker.shrink(
                            kept.get(t),
                            candidate -> {
                                Map<String, List<Long>> trial = new LinkedHashMap<>(kept);
                                trial.put(t, candidate);
                                return reproduces(engine, q, trial, kind);
                            });
            kept.put(t, smaller);
        }
        String ns = "assay.final" + attempt++;
        loadCopy(ns, kept);
        String sql = q.sql().replace(DataGen.NAMESPACE + ".", ns + ".");
        Verdict v = oracle.check(engine, sql, q.ordered());
        return new RowShrink(sql, kept, v);
    }

    private boolean reproduces(EngineProcess engine, Query q, Map<String, List<Long>> rows, Kind kind) {
        String ns = "assay.s" + attempt++;
        try {
            loadCopy(ns, rows);
            String sql = q.sql().replace(DataGen.NAMESPACE + ".", ns + ".");
            boolean same = oracle.check(engine, sql, q.ordered()).kind() == kind;
            dropCopy(ns, rows);
            return same;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadCopy(String ns, Map<String, List<Long>> rows) throws IOException {
        mustExec("CREATE NAMESPACE IF NOT EXISTS " + ns);
        for (Map.Entry<String, List<Long>> e : rows.entrySet()) {
            String target = ns + "." + e.getKey();
            mustExec("CREATE TABLE " + target + " (" + DataGen.columnsDdl() + ") USING iceberg");
            String list = e.getValue().isEmpty() ? "-1" : joined(e.getValue());
            mustExec("INSERT INTO " + target + " SELECT * FROM " + DataGen.NAMESPACE + "." + e.getKey() + " WHERE rid IN (" + list + ")");
        }
    }

    private void dropCopy(String ns, Map<String, List<Long>> rows) throws IOException {
        for (String t : rows.keySet()) {
            mustExec("DROP TABLE " + ns + "." + t + " PURGE");
        }
        mustExec("DROP NAMESPACE " + ns);
    }

    private void mustExec(String sql) throws IOException {
        Reply r = reference.exec(sql, SETUP_TIMEOUT);
        if (!r.ok()) {
            throw new IOException("scratch statement failed: " + r.error() + "\n" + sql);
        }
    }

    private static String joined(List<Long> ids) {
        StringBuilder b = new StringBuilder();
        for (long id : ids) {
            if (b.length() > 0) {
                b.append(",");
            }
            b.append(id);
        }
        return b.toString();
    }

    private static List<String> tablesIn(String sql) {
        return TABLE.matcher(sql).results().map(m -> m.group(1)).distinct().toList();
    }

    /** An engine error names its own cause, so one reason is one bug however the query looks. */
    private static String errorSignature(EngineProcess engine, Verdict v) {
        return v.kind() == Kind.ERROR_DIVERGENCE ? engine.engine + ":" + v.kind() + ":" + errorReason(v.detail()) : null;
    }

    static String errorReason(String detail) {
        Matcher reason = REASON.matcher(detail);
        String text = reason.find() ? reason.group(1) : detail.lines().findFirst().orElse("");
        return text.substring(0, Math.min(text.length(), 160));
    }

    /** Collapses literals, aliases and tables so reshuffled variants of one bug count once. */
    static String normalize(String sql) {
        return sql.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "S")
                .replaceAll("\\ba\\d+\\b", "A")
                .replaceAll("\\bc\\d+\\b", "C")
                .replaceAll("assay\\.\\w+\\.t\\d", "T")
                .replaceAll("-?\\b\\d+(\\.\\d+)?(E-?\\d+)?\\b", "N");
    }

    private String repro(String sql, Map<String, List<Long>> kept) {
        StringBuilder b = new StringBuilder();
        String ns = sql.contains("assay.final") ? sql.replaceAll("(?s).*?(assay\\.final\\d+).*", "$1") : DataGen.NAMESPACE;
        b.append("-- Run on a Spark catalog named `assay`.\n");
        b.append("CREATE NAMESPACE IF NOT EXISTS ").append(ns).append(";\n");
        for (Map.Entry<String, List<Long>> e : kept.entrySet()) {
            b.append("CREATE TABLE ").append(ns).append(".").append(e.getKey());
            b.append(" (").append(DataGen.columnsDdl()).append(") USING iceberg;\n");
            // One statement, because the copy that failed was written in one commit and file
            // layout can decide whether a bug shows up.
            if (!e.getValue().isEmpty()) {
                List<String> tuples = e.getValue().stream().map(data.rows(e.getKey())::get).toList();
                b.append("INSERT INTO ").append(ns).append(".").append(e.getKey());
                b.append(" VALUES ").append(String.join(", ", tuples)).append(";\n");
            }
        }
        b.append(sql).append(";\n");
        return b.toString();
    }

    private String report(EngineProcess engine, Verdict first, Query original, RowShrink rows, Verdict v, int index, Path dir) throws IOException {
        Kind kind = first.kind();
        StringBuilder b = new StringBuilder();
        int rowCount = rows.kept().values().stream().mapToInt(List::size).sum();
        b.append("# ").append(engine.engine).append(" vs spark: ").append(kind).append("\n\n");
        b.append("- Seed ").append(config.seed()).append(", query ").append(index).append("\n");
        b.append("- Reproduced from a clean copy of the shrunk data: ").append(v.kind() == kind ? "yes" : "NO (got " + v.kind() + ")").append("\n");
        b.append("- Data: ").append(rowCount).append(" rows after shrinking\n");
        b.append("- First seen as: ").append(first.detail().lines().limit(6).collect(java.util.stream.Collectors.joining(" / "))).append("\n\n");
        b.append("## Shrunk query\n\n```sql\n").append(rows.sql()).append("\n```\n\n");
        appendResult(b, "spark", dir.resolve("reference.arrow"), v.reference());
        appendResult(b, engine.engine, dir.resolve(engine.engine + ".arrow"), v.tested());
        if (v.tested() != null && v.tested().ok()) {
            b.append("## ").append(engine.engine).append(" final plan\n\n```\n").append(v.tested().plan()).append("\n```\n\n");
        }
        b.append("## Original query\n\n```sql\n").append(original.sql()).append("\n```\n");
        return b.toString();
    }

    private static void appendResult(StringBuilder b, String name, Path arrow, Reply reply) throws IOException {
        b.append("## ").append(name).append(" result\n\n");
        if (reply == null || !reply.ok()) {
            String text = reply == null ? "(not run)" : reply.status() + ": " + reply.error();
            b.append("```\n").append(text.lines().limit(12).collect(java.util.stream.Collectors.joining("\n"))).append("\n```\n\n");
            return;
        }
        ResultSet rs = ResultSet.read(arrow);
        b.append("Types: ").append(rs.types()).append("\n\n```\n");
        rs.rows().stream().limit(20).forEach(r -> b.append(r).append("\n"));
        if (rs.rows().size() > 20) {
            b.append("... ").append(rs.rows().size() - 20).append(" more\n");
        }
        b.append("```\n\n");
    }
}
