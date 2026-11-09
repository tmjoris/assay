package io.github.tmjoris.assay.compare;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether two result sets are the same answer.
 *
 * <p>Doubles get a small relative tolerance, because a parallel SUM of doubles legitimately depends
 * on the order rows arrive in. Decimals and integers must match exactly. Negative zero against
 * positive zero is reported as its own, softer kind of difference.
 */
public final class Comparison {

    /** Relative difference below which two doubles count as the same value. */
    static final double RELATIVE_TOLERANCE = 1e-9;

    private static final int MAX_EXACT_MATCH_ROWS = 5_000;

    public enum Kind {
        MATCH,
        SCHEMA,
        ROW_COUNT,
        VALUE,
        ORDER,
        NEGATIVE_ZERO
    }

    public record Outcome(Kind kind, String detail) {
        public boolean matches() {
            return kind == Kind.MATCH;
        }
    }

    private Comparison() {}

    public static Outcome compare(ResultSet expected, ResultSet actual, boolean ordered) {
        if (!expected.types().equals(actual.types())) {
            return new Outcome(Kind.SCHEMA, "expected types " + expected.types() + " but got " + actual.types());
        }
        if (expected.rows().size() != actual.rows().size()) {
            return new Outcome(
                    Kind.ROW_COUNT,
                    "expected " + expected.rows().size() + " rows but got " + actual.rows().size());
        }
        if (ordered) {
            Outcome strict = compareRows(expected.rows(), actual.rows());
            if (strict.matches() || strict.kind() == Kind.NEGATIVE_ZERO) {
                return strict;
            }
            Outcome unordered = compareUnordered(expected.rows(), actual.rows());
            return unordered.matches()
                    ? new Outcome(Kind.ORDER, "same rows in a different order. " + strict.detail())
                    : unordered;
        }
        return compareUnordered(expected.rows(), actual.rows());
    }

    private static Outcome compareUnordered(List<List<Object>> expected, List<List<Object>> actual) {
        Outcome sorted = compareRows(sorted(expected), sorted(actual));
        if (sorted.matches() || sorted.kind() == Kind.NEGATIVE_ZERO || expected.size() > MAX_EXACT_MATCH_ROWS) {
            return sorted;
        }
        // Sorting can misalign rows whose doubles differ only by tolerated noise. Before calling
        // it a real difference, try pairing the rows up one at a time.
        return pairUp(expected, actual) ? new Outcome(Kind.MATCH, "") : sorted;
    }

    private static boolean pairUp(List<List<Object>> expected, List<List<Object>> actual) {
        List<List<Object>> remaining = new ArrayList<>(actual);
        for (List<Object> row : expected) {
            int found = -1;
            for (int i = 0; i < remaining.size() && found < 0; i++) {
                if (rowDifference(row, remaining.get(i)) == null) {
                    found = i;
                }
            }
            if (found < 0) {
                return false;
            }
            remaining.remove(found);
        }
        return true;
    }

    private static Outcome compareRows(List<List<Object>> expected, List<List<Object>> actual) {
        Outcome softest = new Outcome(Kind.MATCH, "");
        for (int i = 0; i < expected.size(); i++) {
            Outcome cell = rowOutcome(i, expected.get(i), actual.get(i));
            if (cell.kind() == Kind.VALUE) {
                return cell;
            }
            if (cell.kind() == Kind.NEGATIVE_ZERO && softest.matches()) {
                softest = cell;
            }
        }
        return softest;
    }

    private static Outcome rowOutcome(int index, List<Object> e, List<Object> a) {
        Outcome softest = new Outcome(Kind.MATCH, "");
        for (int c = 0; c < e.size(); c++) {
            Kind k = cell(e.get(c), a.get(c));
            String detail = "row " + index + " column " + c + ": expected " + e.get(c) + " but got " + a.get(c);
            if (k == Kind.VALUE) {
                return new Outcome(k, detail);
            }
            if (k == Kind.NEGATIVE_ZERO && softest.matches()) {
                softest = new Outcome(k, detail);
            }
        }
        return softest;
    }

    private static String rowDifference(List<Object> e, List<Object> a) {
        for (int c = 0; c < e.size(); c++) {
            if (cell(e.get(c), a.get(c)) != Kind.MATCH) {
                return "column " + c;
            }
        }
        return null;
    }

    private static Kind cell(Object e, Object a) {
        if (e == null || a == null) {
            return e == a ? Kind.MATCH : Kind.VALUE;
        }
        if (e instanceof Double x && a instanceof Double y) {
            return doubles(x, y);
        }
        if (e instanceof BigDecimal x && a instanceof BigDecimal y) {
            return x.compareTo(y) == 0 ? Kind.MATCH : Kind.VALUE;
        }
        return Objects.equals(e, a) ? Kind.MATCH : Kind.VALUE;
    }

    private static Kind doubles(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.isNaN(x) && Double.isNaN(y) ? Kind.MATCH : Kind.VALUE;
        }
        if (x == y) {
            return Double.doubleToLongBits(x) == Double.doubleToLongBits(y) ? Kind.MATCH : Kind.NEGATIVE_ZERO;
        }
        if (Double.isInfinite(x) || Double.isInfinite(y)) {
            return Kind.VALUE;
        }
        return Math.abs(x - y) <= RELATIVE_TOLERANCE * Math.max(Math.abs(x), Math.abs(y)) ? Kind.MATCH : Kind.VALUE;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(Comparison::sortKey));
        return copy;
    }

    /** A key that sorts equal rows together, ignoring the bits of a double within tolerance. */
    private static String sortKey(List<Object> row) {
        StringBuilder b = new StringBuilder();
        for (Object v : row) {
            if (v instanceof Double d) {
                b.append(d == 0.0 ? "0" : String.format("%.8e", d));
            } else if (v instanceof BigDecimal m) {
                b.append(m.signum() == 0 ? "0" : m.stripTrailingZeros().toPlainString());
            } else {
                b.append(v);
            }
            b.append('\u0001');
        }
        return b.toString();
    }
}
