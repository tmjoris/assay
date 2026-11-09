package io.github.tmjoris.assay.cli;

import io.github.tmjoris.assay.cli.EngineProcess.Reply;
import io.github.tmjoris.assay.compare.ResultSet;
import io.github.tmjoris.assay.run.PlanStats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a SQL script against several engines and prints each answer side by side.
 *
 * <p>Statements other than SELECT run once, on plain Spark. This is how a {@code repro.sql} from a
 * finding gets rechecked or picked apart by hand.
 */
final class Check {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private Check() {}

    static void run(Path script, List<String> engines, Path out, Path classpaths, String timeZone) throws IOException {
        Path warehouse = out.resolve("warehouse").toAbsolutePath();
        Path logs = Files.createDirectories(out.resolve("logs"));
        List<String> statements = statements(Files.readString(script));
        try (EngineProcess spark = open("spark", classpaths, warehouse, timeZone, logs)) {
            for (String sql : statements) {
                if (!isQuery(sql)) {
                    Reply r = spark.exec(sql, TIMEOUT);
                    if (!r.ok()) {
                        throw new IOException("failed: " + r.error() + "\n" + sql);
                    }
                }
            }
            print(spark, statements, out);
            for (String engine : engines) {
                try (EngineProcess e = open(engine, classpaths, warehouse, timeZone, logs)) {
                    print(e, statements, out);
                }
            }
        }
    }

    private static EngineProcess open(String engine, Path classpaths, Path warehouse, String timeZone, Path logs) throws IOException {
        return new EngineProcess(engine, classpaths, warehouse.toString(), timeZone, logs.resolve(engine + ".log"));
    }

    private static void print(EngineProcess e, List<String> statements, Path out) throws IOException {
        Path arrow = out.resolve(e.engine + ".arrow");
        for (String sql : statements) {
            if (isQuery(sql)) {
                Reply r = e.run(sql, arrow, TIMEOUT);
                System.out.println("[" + e.engine + "] " + sql);
                if (r.ok()) {
                    PlanStats p = PlanStats.parse(r.plan());
                    System.out.println("  native operators " + p.nativeOperators() + ", jvm operators " + p.jvmOperators());
                    ResultSet.read(arrow).rows().forEach(row -> System.out.println("  " + row));
                } else {
                    System.out.println("  " + r.status() + ": " + r.error());
                }
            }
        }
    }

    static boolean isQuery(String sql) {
        String s = sql.stripLeading().toUpperCase(Locale.ROOT);
        return s.startsWith("SELECT") || s.startsWith("(SELECT");
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
