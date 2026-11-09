package io.github.tmjoris.assay.cli;

import io.github.tmjoris.assay.cli.EngineProcess.Reply;
import io.github.tmjoris.assay.compare.Comparison;
import io.github.tmjoris.assay.compare.ResultSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;

/** Runs one query on the reference engine and one engine under test, and says how they differ. */
final class Oracle {

    enum Kind {
        SCHEMA,
        ROW_COUNT,
        VALUE,
        ORDER,
        NEGATIVE_ZERO,
        ERROR_DIVERGENCE,
        CRASH,
        TIMEOUT
    }

    /**
     * What a run established.
     *
     * @param kind the difference found, or null if the engines agree or the query was unusable
     * @param usable false when the reference engine rejected the query, which says nothing
     */
    record Verdict(Kind kind, boolean usable, String detail, Reply reference, Reply tested) {
        boolean differs() {
            return kind != null;
        }
    }

    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final EngineProcess reference;
    private final Path scratch;

    Oracle(EngineProcess reference, Path scratch) {
        this.reference = reference;
        this.scratch = scratch;
    }

    Verdict check(EngineProcess tested, String sql, boolean ordered) {
        try {
            Path expectedFile = scratch.resolve("reference.arrow");
            Reply expected = reference.run(sql, expectedFile, TIMEOUT);
            if (!expected.ok()) {
                return new Verdict(null, false, expected.error(), expected, null);
            }
            Path actualFile = scratch.resolve(tested.engine + ".arrow");
            Reply actual = tested.run(sql, actualFile, TIMEOUT);
            return switch (actual.status()) {
                case CRASH -> new Verdict(Kind.CRASH, true, actual.error(), expected, actual);
                case TIMEOUT -> new Verdict(Kind.TIMEOUT, true, actual.error(), expected, actual);
                case ERROR -> new Verdict(Kind.ERROR_DIVERGENCE, true, actual.error(), expected, actual);
                case OK -> compare(expectedFile, actualFile, ordered, expected, actual);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Verdict compare(Path expectedFile, Path actualFile, boolean ordered, Reply expected, Reply actual)
            throws IOException {
        Comparison.Outcome o =
                Comparison.compare(ResultSet.read(expectedFile), ResultSet.read(actualFile), ordered);
        Kind kind =
                switch (o.kind()) {
                    case MATCH -> null;
                    case SCHEMA -> Kind.SCHEMA;
                    case ROW_COUNT -> Kind.ROW_COUNT;
                    case VALUE -> Kind.VALUE;
                    case ORDER -> Kind.ORDER;
                    case NEGATIVE_ZERO -> Kind.NEGATIVE_ZERO;
                };
        return new Verdict(kind, true, o.detail(), expected, actual);
    }

    Path resultFile(String engine) {
        return scratch.resolve(engine + ".arrow");
    }
}
