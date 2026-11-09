package io.github.tmjoris.assay.cli;

import io.github.tmjoris.assay.run.Protocol;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A running worker JVM for one engine. A native crash ends up here as a {@link Reply.Status}. */
final class EngineProcess implements AutoCloseable {

    record Reply(Status status, long rows, long millis, String plan, String error) {
        enum Status {
            OK,
            ERROR,
            CRASH,
            TIMEOUT
        }

        boolean ok() {
            return status == Status.OK;
        }
    }

    private static final List<String> JVM_FLAGS =
            List.of(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.nio=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
                    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
                    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
                    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
                    "-Dio.netty.tryReflectionSetAccessible=true");

    private static final String EOF = "\u0000EOF";

    final String engine;
    private final Path classpaths;
    private final String warehouse;
    private final String timeZone;
    private final Path log;
    private Process process;
    private BufferedWriter in;
    private BlockingQueue<String> lines;

    EngineProcess(String engine, Path classpaths, String warehouse, String timeZone, Path log) throws IOException {
        this.engine = engine;
        this.classpaths = classpaths;
        this.warehouse = warehouse;
        this.timeZone = timeZone;
        this.log = log;
        start();
    }

    private void start() throws IOException {
        String classpath = Files.readString(classpaths.resolve(engine + ".txt"));
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx2g"));
        cmd.addAll(JVM_FLAGS);
        cmd.addAll(List.of("-cp", classpath, "io.github.tmjoris.assay.worker.Worker", engine, warehouse, timeZone));
        process =
                new ProcessBuilder(cmd)
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
        in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        lines = queue;
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread reader =
                new Thread(
                        () -> {
                            try {
                                String line;
                                while ((line = out.readLine()) != null) {
                                    queue.add(line);
                                }
                            } catch (IOException ignored) {
                                // The process died. The EOF marker below reports that.
                            }
                            queue.add(EOF);
                        },
                        "assay-" + engine + "-reader");
        reader.setDaemon(true);
        reader.start();
        String first = poll(Duration.ofMinutes(4));
        if (!Protocol.READY.equals(first)) {
            throw new IOException(engine + " worker failed to start, see " + log + " (got " + first + ")");
        }
    }

    Reply run(String sql, Path arrow, Duration timeout) throws IOException {
        return send("RUN\t" + Protocol.encode(sql) + "\t" + arrow, timeout);
    }

    Reply exec(String sql, Duration timeout) throws IOException {
        return send("EXEC\t" + Protocol.encode(sql), timeout);
    }

    private Reply send(String request, Duration timeout) throws IOException {
        try {
            in.write(request);
            in.newLine();
            in.flush();
        } catch (IOException dead) {
            restart();
            return new Reply(Reply.Status.CRASH, 0, 0, "", "worker pipe closed: " + dead.getMessage());
        }
        String line = poll(timeout);
        if (line == null) {
            restart();
            return new Reply(Reply.Status.TIMEOUT, 0, 0, "", "no reply within " + timeout);
        }
        if (line.equals(EOF)) {
            int code = waitFor();
            restart();
            return new Reply(Reply.Status.CRASH, 0, 0, "", "worker exited with code " + code);
        }
        String[] f = line.split("\t", -1);
        if (f[0].equals("OK")) {
            return new Reply(Reply.Status.OK, Long.parseLong(f[1]), Long.parseLong(f[2]), Protocol.decode(f[3]), "");
        }
        return new Reply(Reply.Status.ERROR, 0, 0, "", Protocol.decode(f[1]));
    }

    private String poll(Duration timeout) throws IOException {
        try {
            return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + engine, e);
        }
    }

    private int waitFor() {
        try {
            return process.waitFor(10, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void restart() throws IOException {
        process.destroyForcibly();
        start();
    }

    @Override
    public void close() {
        try {
            in.write("QUIT");
            in.newLine();
            in.flush();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
