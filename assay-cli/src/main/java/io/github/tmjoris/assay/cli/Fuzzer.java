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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

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
            int maxShrinks,
            int start,
            int parallel) {}

    /** Counters for one engine under test. */
    static final class EngineStats {
        int compared;
        int anyNative;
        int fullyNative;
        long millis;
        final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);

        synchronized void add(Verdict v, PlanStats plan) {
            compared++;
            if (v.kind() != null) {
                kinds.merge(v.kind(), 1, Integer::sum);
            }
            if (plan != null) {
                millis += v.tested().millis();
                anyNative += plan.anyNative() ? 1 : 0;
                fullyNative += plan.fullyNative() ? 1 : 0;
            }
        }
    }

    /** A full set of workers. A lane handles one query at a time, so nothing inside it is shared. */
    private record Lane(EngineProcess reference, Oracle oracle, List<EngineProcess> tested) {
        void close() {
            tested.forEach(EngineProcess::close);
            reference.close();
        }
    }

    private record Indexed(int index, Query query) {}

    private static final Pattern TABLE = Pattern.compile("assay\\.d\\.(t\\d)");
    private static final Pattern REASON = Pattern.compile("Reason: (.*)");
    private static final Duration SETUP_TIMEOUT = Duration.ofMinutes(3);

    /** A difference must recur this many more times before it is treated as a finding. */
    private static final int RECHECKS = 2;

    private final Config config;
    private final Map<String, EngineStats> stats = new LinkedHashMap<>();
    private final Map<String, Integer> findings = new ConcurrentHashMap<>();
    /** One line per query that ran, keyed by index, so the file is the same at any parallelism. */
    private final Map<Integer, String> verdicts = new ConcurrentSkipListMap<>();
    private final AtomicInteger unusable = new AtomicInteger();
    private final AtomicInteger shrinks = new AtomicInteger();
    private final AtomicInteger flaky = new AtomicInteger();
    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicInteger findingId = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final Object unshrunkLock = new Object();
    private DataGen data;

    Fuzzer(Config config) {
        this.config = config;
    }

    void run() throws Exception {
        Path warehouse = config.out().resolve("warehouse").toAbsolutePath();
        Files.createDirectories(config.out().resolve("logs"));
        Files.createDirectories(config.out().resolve("findings"));
        long started = System.nanoTime();

        EngineProcess first = open("spark", warehouse, 0);
        data = new DataGen(config.seed(), config.rows());
        for (String sql : data.setup()) {
            Reply r = first.exec(sql, SETUP_TIMEOUT);
            if (!r.ok()) {
                throw new IOException("setup failed: " + r.error() + "\n" + sql);
            }
        }
        config.engines().forEach(e -> stats.put(e, new EngineStats()));

        List<Lane> lanes = new ArrayList<>();
        try {
            openLanes(first, warehouse, lanes);
            new LanePool<>(lanes)
                    .runAll(
                            queries(),
                            (lane, item) -> runQuery(lane, item.query(), item.index()));
        } finally {
            lanes.forEach(Lane::close);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        Files.write(config.out().resolve("results.tsv"), verdicts.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList());
        Summary.write(config, stats, findings, unusable.get(), flaky.get(), shrinks.get(), seconds);
    }

    /** Starts every lane's workers at once, since each JVM takes many seconds to come up. */
    private void openLanes(EngineProcess first, Path warehouse, List<Lane> lanes) throws Exception {
        List<Future<Lane>> pending = new ArrayList<>();
        try (ExecutorService starter = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < config.parallel(); i++) {
                int laneId = i;
                pending.add(starter.submit(() -> openLane(laneId, laneId == 0 ? first : open("spark", warehouse, laneId), warehouse)));
            }
        }
        for (Future<Lane> lane : pending) {
            lanes.add(lane.get());
        }
    }

    private Lane openLane(int id, EngineProcess reference, Path warehouse) throws IOException {
        List<EngineProcess> tested = new ArrayList<>();
        for (String engine : config.engines()) {
            tested.add(open(engine, warehouse, id));
        }
        Path scratch = Files.createDirectories(config.out().resolve("scratch").resolve("lane-" + id));
        return new Lane(reference, new Oracle(reference, scratch), tested);
    }

    /** Generates queries on the caller's thread, skipping any before the start index. */
    private Iterator<Indexed> queries() {
        QueryGen gen = new QueryGen(config.seed());
        return IntStream.range(0, config.queries())
                .mapToObj(i -> new Indexed(i, gen.next()))
                .filter(q -> q.index() >= config.start())
                .iterator();
    }

    private void runQuery(Lane lane, Query q, int index) throws IOException {
        StringBuilder outcome = new StringBuilder();
        for (EngineProcess engine : lane.tested()) {
            Verdict v = lane.oracle().check(engine, q.sql(), q.ordered());
            if (!v.usable()) {
                unusable.incrementAndGet();
                outcome.append("unusable");
                break;
            }
            record(engine, v);
            outcome.append(engine.engine).append('=').append(v.kind() == null ? "same" : v.kind()).append(' ');
            if (v.differs()) {
                handleDifference(lane, engine, q, v, index);
            }
        }
        verdicts.put(index, outcome.toString().strip());
        int done = completed.incrementAndGet();
        if (done % 25 == 0) {
            System.out.printf("%d/%d queries, %d unusable, %d distinct findings%n", done, config.queries() - config.start(), unusable.get(), findings.size());
        }
    }

    private EngineProcess open(String engine, Path warehouse, int lane) throws IOException {
        Path log = config.out().resolve("logs").resolve("lane-" + lane + "-" + engine + ".log");
        return new EngineProcess(engine, config.classpaths(), warehouse.toString(), config.timeZone(), log);
    }

    private void record(EngineProcess engine, Verdict v) {
        boolean ran = v.tested() != null && v.tested().ok();
        stats.get(engine.engine).add(v, ran ? PlanStats.parse(v.tested().plan()) : null);
    }

    private void handleDifference(Lane lane, EngineProcess engine, Query original, Verdict first, int index) throws IOException {
        Oracle oracle = lane.oracle();
        for (int i = 0; i < RECHECKS; i++) {
            if (oracle.check(engine, original.sql(), original.ordered()).kind() != first.kind()) {
                flaky.incrementAndGet();
                return;
            }
        }
        // merge is atomic, so when two lanes hit the same error only one goes on to shrink it.
        String errorSignature = errorSignature(engine, first);
        if (errorSignature != null && findings.merge(errorSignature, 1, Integer::sum) > 1) {
            return;
        }
        int max = config.maxShrinks();
        if (shrinks.getAndUpdate(n -> n < max ? n + 1 : n) >= max) {
            synchronized (unshrunkLock) {
                Files.writeString(
                        config.out().resolve("unshrunk.txt"),
                        engine.engine + "\t" + first.kind() + "\t" + index + "\t" + original.sql() + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            return;
        }
        Shrinker.Result small =
                new Shrinker(config.shrinkBudget())
                        .shrink(original, q -> oracle.check(engine, q.sql(), q.ordered()).kind() == first.kind());
        Query q = small.query();
        if (errorSignature == null && findings.merge(engine.engine + ":" + first.kind() + ":" + normalize(q.sql()), 1, Integer::sum) > 1) {
            return;
        }

        RowShrink rows = shrinkRows(lane, engine, q, first.kind());
        Verdict finalVerdict = rows.verdict();
        String id = String.format("%04d-%s-%s", findingId.incrementAndGet(), engine.engine, first.kind().name().toLowerCase(Locale.ROOT));
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
    private RowShrink shrinkRows(Lane lane, EngineProcess engine, Query q, Kind kind) throws IOException {
        List<String> tables = tablesIn(q.sql());
        Map<String, List<Long>> kept = new LinkedHashMap<>();
        for (String t : tables) {
            kept.put(t, new ArrayList<>(data.rows(t).keySet()));
        }
        // A copy has one simple file layout. If the failure needs the original layout, shrinking
        // copies can only waste the budget, so keep the full data and let the report say so.
        if (!reproduces(lane, engine, q, kept, kind)) {
            return new RowShrink(q.sql(), kept, lane.oracle().check(engine, q.sql(), q.ordered()));
        }
        DataShrinker shrinker = new DataShrinker(config.shrinkBudget() / 2);
        for (String t : tables) {
            List<Long> smaller =
                    shrinker.shrink(
                            kept.get(t),
                            candidate -> {
                                Map<String, List<Long>> trial = new LinkedHashMap<>(kept);
                                trial.put(t, candidate);
                                return reproduces(lane, engine, q, trial, kind);
                            });
            kept.put(t, smaller);
        }
        String ns = "assay.final" + attempt.getAndIncrement();
        loadCopy(lane, ns, kept);
        String sql = q.sql().replace(DataGen.NAMESPACE + ".", ns + ".");
        Verdict v = lane.oracle().check(engine, sql, q.ordered());
        return new RowShrink(sql, kept, v);
    }

    private boolean reproduces(Lane lane, EngineProcess engine, Query q, Map<String, List<Long>> rows, Kind kind) {
        String ns = "assay.s" + attempt.getAndIncrement();
        try {
            loadCopy(lane, ns, rows);
            String sql = q.sql().replace(DataGen.NAMESPACE + ".", ns + ".");
            boolean same = lane.oracle().check(engine, sql, q.ordered()).kind() == kind;
            dropCopy(lane, ns, rows);
            return same;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadCopy(Lane lane, String ns, Map<String, List<Long>> rows) throws IOException {
        mustExec(lane, "CREATE NAMESPACE IF NOT EXISTS " + ns);
        for (Map.Entry<String, List<Long>> e : rows.entrySet()) {
            String target = ns + "." + e.getKey();
            mustExec(lane, "CREATE TABLE " + target + " (" + DataGen.columnsDdl() + ") USING iceberg");
            String list = e.getValue().isEmpty() ? "-1" : joined(e.getValue());
            mustExec(lane, "INSERT INTO " + target + " SELECT * FROM " + DataGen.NAMESPACE + "." + e.getKey() + " WHERE rid IN (" + list + ")");
        }
    }

    private void dropCopy(Lane lane, String ns, Map<String, List<Long>> rows) throws IOException {
        for (String t : rows.keySet()) {
            mustExec(lane, "DROP TABLE " + ns + "." + t + " PURGE");
        }
        mustExec(lane, "DROP NAMESPACE " + ns);
    }

    private void mustExec(Lane lane, String sql) throws IOException {
        Reply r = lane.reference().exec(sql, SETUP_TIMEOUT);
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
