package io.github.tmjoris.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DataShrinkerTest {

    private static final List<Integer> ROWS = IntStream.range(0, 200).boxed().toList();

    @Test
    void findsTheSingleRowThatMatters() {
        assertEquals(List.of(137), new DataShrinker(10_000).shrink(ROWS, r -> r.contains(137)));
    }

    @Test
    void keepsEveryRowOfAMultiRowCause() {
        List<Integer> kept = new DataShrinker(10_000).shrink(ROWS, r -> r.contains(3) && r.contains(150));
        assertEquals(List.of(3, 150), kept);
    }

    @Test
    void canShrinkToNothingWhenTheBugNeedsNoData() {
        assertEquals(List.of(), new DataShrinker(10_000).shrink(ROWS, r -> true));
    }

    @Test
    void stopsAtTheBudget() {
        DataShrinker s = new DataShrinker(5);
        s.shrink(ROWS, r -> r.contains(137));
        assertTrue(s.tests() <= 5);
    }

    @Test
    void normalizationCollapsesLiteralsAndAliases() {
        assertEquals(
                Fuzzer.normalize("SELECT abs(a1.k) AS c0 FROM assay.d.t0 a1 WHERE a1.s = 'x' AND a1.k = 5"),
                Fuzzer.normalize("SELECT abs(a7.k) AS c0 FROM assay.final3.t2 a7 WHERE a7.s = 'o\\'b' AND a7.k = 99"));
    }
}
