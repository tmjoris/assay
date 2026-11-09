package io.github.tmjoris.assay.cli;

import io.github.tmjoris.assay.cli.EngineProcess.Reply;
import io.github.tmjoris.assay.compare.Comparison;
import io.github.tmjoris.assay.compare.ResultSet;
import io.github.tmjoris.assay.run.PlanStats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs a SQL script against several engines and prints each answer side by side.
 *
 * <p>Statements other than SELECT run once, on plain Spark. A script can carry two headers:
 * {@code -- tz: ZONE} sets the session time zone, and {@code -- expect-differs: velox,comet} makes
 * the run fail unless exactly those engines disagree with Spark. That turns each recorded finding
 * into a regression check.
 */
final class Check {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** One engine's answer to one SELECT: its rows, or the error it raised. */
    private record Answer(ResultSet rows, String error) {}

    private Check() {}

    /** @param existing a warehouse from an earlier run to query, or null to start with an empty one */
    static int run(Path script, List<String> engines, Path out, Path classpaths, String timeZone, Path existing) throws IOException {
        Path warehouse = existing != null ? existing.toAbsolutePath() : out.resolve("warehouse").toAbsolutePath();
        Path logs = Files.createDirectories(out.resolve("logs"));
        String text = Files.readString(script);
        List<String> statements = statements(text);
        String zone = header(text, "tz").orElse(timeZone);
        Optional<List<String>> expected = header(text, "expect-differs").map(v -> Arrays.asList(v.split(",")));

        List<String> differing = new ArrayList<>();
        try (EngineProcess spark = open("spark", classpaths, warehouse, zone, logs)) {
            for (String sql : statements) {
                if (!isQuery(sql)) {
                    Reply r = spark.exec(sql, TIMEOUT);
                    if (!r.ok()) {
                        throw new IOException("failed: " + r.error() + "\n" + sql);
                    }
                }
            }
            List<Answer> reference = answers(spark, statements, out);
            for (String engine : engines) {
                try (EngineProcess e = open(engine, classpaths, warehouse, zone, logs)) {
                    if (differs(reference, answers(e, statements, out))) {
                        differing.add(engine);
                    }
                }
            }
        }
        System.out.println("engines that differ from spark: " + (differing.isEmpty() ? "none" : String.join(", ", differing)));
        if (expected.isEmpty()) {
            return 0;
        }
        boolean as = differing.containsAll(expected.get()) && expected.get().containsAll(differing);
        System.out.println(as ? "matches the recorded finding" : "DOES NOT match the recorded finding, expected " + expected.get());
        return as ? 0 : 1;
    }

    private static EngineProcess open(String engine, Path classpaths, Path warehouse, String timeZone, Path logs) throws IOException {
        return new EngineProcess(engine, classpaths, warehouse.toString(), timeZone, logs.resolve(engine + ".log"));
    }

    private static List<Answer> answers(EngineProcess e, List<String> statements, Path out) throws IOException {
        Path arrow = out.resolve(e.engine + ".arrow");
        List<Answer> answers = new ArrayList<>();
        for (String sql : statements) {
            if (!isQuery(sql)) {
                continue;
            }
            Reply r = e.run(sql, arrow, TIMEOUT);
            System.out.println("[" + e.engine + "] " + sql);
            if (r.ok()) {
                PlanStats p = PlanStats.parse(r.plan());
                System.out.println("  native operators " + p.nativeOperators() + ", jvm operators " + p.jvmOperators());
                ResultSet rows = ResultSet.read(arrow);
                rows.rows().forEach(row -> System.out.println("  " + row));
                answers.add(new Answer(rows, null));
            } else {
                System.out.println("  " + r.status() + ": " + r.error().lines().limit(4).toList());
                answers.add(new Answer(null, r.status() + ""));
            }
        }
        return answers;
    }

    private static boolean differs(List<Answer> reference, List<Answer> tested) {
        for (int i = 0; i < reference.size(); i++) {
            Answer a = reference.get(i);
            Answer b = tested.get(i);
            if (a.rows() == null || b.rows() == null) {
                if (a.rows() != b.rows()) {
                    return true;
                }
            } else if (!Comparison.compare(a.rows(), b.rows(), false).matches()) {
                return true;
            }
        }
        return false;
    }

    static boolean isQuery(String sql) {
        String s = sql.stripLeading().toUpperCase(Locale.ROOT);
        return s.startsWith("SELECT") || s.startsWith("(SELECT");
    }

    static Optional<String> header(String script, String name) {
        String prefix = "-- " + name + ":";
        return script.lines().filter(l -> l.startsWith(prefix)).map(l -> l.substring(prefix.length()).strip()).findFirst();
    }

    /** Splits on a semicolon at the end of a line, so one inside a string literal is left alone. */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            if (line.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String sql = current.toString().strip();
                out.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        return out;
    }
}
