package io.github.tmjoris.assay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.tmjoris.assay.compare.Comparison;
import io.github.tmjoris.assay.compare.Comparison.Kind;
import io.github.tmjoris.assay.compare.ResultSet;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonTest {

    private static ResultSet rs(String type, Object... values) {
        return new ResultSet(List.of(type), Arrays.stream(values).map(v -> Arrays.asList(v)).toList());
    }

    private static Kind kind(ResultSet a, ResultSet b, boolean ordered) {
        return Comparison.compare(a, b, ordered).kind();
    }

    @Test
    void rowOrderIgnoredUnlessPromised() {
        assertEquals(Kind.MATCH, kind(rs("int", 1, 2), rs("int", 2, 1), false));
        assertEquals(Kind.ORDER, kind(rs("int", 1, 2), rs("int", 2, 1), true));
    }

    @Test
    void multisetsRespectDuplicates() {
        assertEquals(Kind.VALUE, kind(rs("int", 1, 1, 2), rs("int", 1, 2, 2), false));
    }

    @Test
    void nullsAreOnlyEqualToNulls() {
        assertEquals(Kind.MATCH, kind(rs("int", (Object) null), rs("int", (Object) null), false));
        assertEquals(Kind.VALUE, kind(rs("int", 0), rs("int", (Object) null), false));
    }

    @Test
    void differentCountsAndTypesAreReported() {
        assertEquals(Kind.ROW_COUNT, kind(rs("int", 1), rs("int", 1, 2), false));
        assertEquals(Kind.SCHEMA, kind(rs("int", 1), rs("bigint", 1), false));
    }

    @Test
    void floatingPointNoiseIsTolerated() {
        assertEquals(Kind.MATCH, kind(rs("double", 1.0), rs("double", 1.0 + 1e-12), false));
        assertEquals(Kind.VALUE, kind(rs("double", 1.0), rs("double", 1.0001), false));
    }

    @Test
    void nanEqualsNanAndInfinityIsExact() {
        assertEquals(Kind.MATCH, kind(rs("double", Double.NaN), rs("double", Double.NaN), false));
        assertEquals(Kind.VALUE, kind(rs("double", Double.NaN), rs("double", 1.0), false));
        assertEquals(Kind.VALUE, kind(rs("double", Double.POSITIVE_INFINITY), rs("double", Double.MAX_VALUE), false));
    }

    @Test
    void negativeZeroIsItsOwnKind() {
        assertEquals(Kind.NEGATIVE_ZERO, kind(rs("double", -0.0), rs("double", 0.0), false));
    }

    @Test
    void decimalsCompareByValueAndHaveNoTolerance() {
        assertEquals(Kind.MATCH, kind(rs("decimal", new BigDecimal("1.50")), rs("decimal", new BigDecimal("1.5")), false));
        assertEquals(
                Kind.VALUE,
                kind(rs("decimal", new BigDecimal("1.0000000001")), rs("decimal", new BigDecimal("1.0000000002")), false));
    }

    @Test
    void noisyDoublesThatSortDifferentlyStillPairUp() {
        double a = 1.2345678949999;
        double b = a * (1 + 5e-10);
        ResultSet x = new ResultSet(List.of("double", "int"), List.of(Arrays.asList(a, 1), Arrays.asList(5.0, 2)));
        ResultSet y = new ResultSet(List.of("double", "int"), List.of(Arrays.asList(5.0, 2), Arrays.asList(b, 1)));
        assertEquals(Kind.MATCH, kind(x, y, false));
    }
}
