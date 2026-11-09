package io.github.tmjoris.assay.cli;

import io.github.tmjoris.assay.cli.Fuzzer.Config;
import io.github.tmjoris.assay.cli.Fuzzer.EngineStats;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/** Writes the machine-readable result of a run and prints a short human one. */
final class Summary {

    private Summary() {}

    static void write(Config c, Map<String, EngineStats> stats, Map<String, Integer> findings, int unusable, int flaky, int shrinks, double seconds)
            throws IOException {
        StringBuilder j = new StringBuilder("{\n");
        j.append("  \"seed\": ").append(c.seed()).append(",\n");
        j.append("  \"queries\": ").append(c.queries()).append(",\n");
        j.append("  \"rows_per_table\": ").append(c.rows()).append(",\n");
        j.append("  \"time_zone\": \"").append(c.timeZone()).append("\",\n");
        j.append("  \"unusable\": ").append(unusable).append(",\n");
        j.append("  \"flaky\": ").append(flaky).append(",\n");
        j.append("  \"shrunk\": ").append(shrinks).append(",\n");
        j.append("  \"distinct_findings\": ").append(findings.size()).append(",\n");
        j.append(String.format("  \"seconds\": %.1f,%n", seconds));
        j.append("  \"engines\": {\n");
        int e = 0;
        for (Map.Entry<String, EngineStats> en : stats.entrySet()) {
            EngineStats s = en.getValue();
            j.append("    \"").append(en.getKey()).append("\": {");
            j.append("\"compared\": ").append(s.compared);
            j.append(", \"any_native\": ").append(s.anyNative);
            j.append(", \"fully_native\": ").append(s.fullyNative);
            j.append(", \"millis\": ").append(s.millis);
            j.append(", \"differences\": {");
            int k = 0;
            for (var kv : s.kinds.entrySet()) {
                j.append(k++ == 0 ? "" : ", ").append('"').append(kv.getKey()).append("\": ").append(kv.getValue());
            }
            j.append("}}").append(++e < stats.size() ? ",\n" : "\n");
        }
        j.append("  }\n}\n");
        Files.writeString(c.out().resolve("summary.json"), j.toString());
        System.out.print(j);
    }
}
