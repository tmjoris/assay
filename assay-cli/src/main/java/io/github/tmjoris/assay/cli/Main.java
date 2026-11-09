package io.github.tmjoris.assay.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Command line entry point. See the README for usage. */
public final class Main {

    private static final String USAGE =
            """
            usage: assay check --out DIR --file SCRIPT.sql [--engines a,b] [--tz ZONE]
                   assay --out DIR [--seed N] [--queries N] [--rows N] [--engines a,b]
                         [--tz ZONE] [--shrink-budget N] [--max-shrinks N]
                         [--classpaths DIR]
            """;

    private Main() {}

    public static void main(String[] args) throws IOException {
        boolean check = args.length > 0 && args[0].equals("check");
        Map<String, String> opts = parse(check ? Arrays.copyOfRange(args, 1, args.length) : args);
        if (!opts.containsKey("out")) {
            System.err.print(USAGE);
            System.exit(2);
        }
        Path out = Path.of(opts.get("out"));
        if (Files.exists(out)) {
            System.err.println("output directory already exists: " + out);
            System.exit(2);
        }
        Files.createDirectories(out);
        List<String> engines = Arrays.asList(opts.getOrDefault("engines", "comet,velox").split(","));
        Path classpaths = Path.of(opts.getOrDefault("classpaths", "assay-worker/build/classpaths"));
        if (check) {
            Check.run(Path.of(opts.get("file")), engines, out, classpaths, opts.getOrDefault("tz", "UTC"));
            return;
        }
        new Fuzzer(
                        new Fuzzer.Config(
                                Long.parseLong(opts.getOrDefault("seed", "1")),
                                Integer.parseInt(opts.getOrDefault("queries", "200")),
                                Integer.parseInt(opts.getOrDefault("rows", "200")),
                                engines,
                                out,
                                classpaths,
                                opts.getOrDefault("tz", "UTC"),
                                Integer.parseInt(opts.getOrDefault("shrink-budget", "200")),
                                Integer.parseInt(opts.getOrDefault("max-shrinks", "30"))))
                .run();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected an option but got " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        return opts;
    }
}
