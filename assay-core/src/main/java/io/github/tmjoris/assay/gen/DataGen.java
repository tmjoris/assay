package io.github.tmjoris.assay.gen;

import io.github.tmjoris.assay.sql.Expr;
import io.github.tmjoris.assay.sql.SqlType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Builds the test tables, deterministically from a seed.
 *
 * <p>Every table has the same columns. Values are drawn from a small domain so joins and group-bys
 * find matches, mixed with the values engines most often disagree on: nulls, NaN, infinities,
 * negative zero, integer and decimal limits, strings with awkward bytes, and extreme dates.
 *
 * <p>The tables differ in physical layout, because a bug can live in the scan as easily as in an
 * operator: {@code t0} is a plain append table, {@code t1} is written as several files, {@code t2}
 * is partitioned, and {@code t3} has merge-on-read delete files.
 */
public final class DataGen {

    public static final String NAMESPACE = "assay.d";
    public static final List<String> TABLES = List.of("t0", "t1", "t2", "t3");

    public static final List<Expr.Col> COLUMNS =
            List.of(
                    new Expr.Col("", "rid", SqlType.BIGINT),
                    new Expr.Col("", "k", SqlType.INT),
                    new Expr.Col("", "l", SqlType.BIGINT),
                    new Expr.Col("", "d", SqlType.DOUBLE),
                    new Expr.Col("", "m", SqlType.DEC18),
                    new Expr.Col("", "w", SqlType.DEC38),
                    new Expr.Col("", "s", SqlType.STRING),
                    new Expr.Col("", "dt", SqlType.DATE),
                    new Expr.Col("", "ts", SqlType.TIMESTAMP),
                    new Expr.Col("", "b", SqlType.BOOLEAN));

    private static final List<String> INTS =
            List.of("0", "1", "-1", "2147483647", "-2147483648", "7", "100");
    private static final List<String> LONGS =
            List.of("0", "1", "-1", "9223372036854775807", "(-9223372036854775807 - 1)", "4294967296");
    private static final List<String> DOUBLES =
            List.of(
                    "CAST('NaN' AS DOUBLE)",
                    "CAST('Infinity' AS DOUBLE)",
                    "CAST('-Infinity' AS DOUBLE)",
                    "CAST(0.0 AS DOUBLE)",
                    "CAST(-0.0 AS DOUBLE)",
                    "CAST(1.7976931348623157E308 AS DOUBLE)",
                    "CAST(4.9E-324 AS DOUBLE)",
                    "CAST(0.1 AS DOUBLE)",
                    "CAST(2.5 AS DOUBLE)",
                    "CAST(-2.5 AS DOUBLE)");
    private static final List<String> DEC18S =
            List.of("0.0000", "0.0001", "-0.0001", "99999999999999.9999", "-99999999999999.9999", "2.5000");
    private static final List<String> DEC38S =
            List.of(
                    "0.0000000000",
                    "0.0000000001",
                    "-0.0000000001",
                    "9999999999999999999999999999.9999999999",
                    "-9999999999999999999999999999.9999999999");
    private static final List<String> STRINGS =
            List.of(
                    "", " ", "a", "A", " a ", "abc", "ABC", "NULL", "%", "_", "o\\'brien", "back\\\\slash",
                    "é", "ß", "İ", "日本語", "🦀", "a🦀b", "tab\\tend", "123", "-1.5", "2024-01-15", "true");
    private static final List<String> DATES =
            List.of("1970-01-01", "1969-12-31", "2000-02-29", "2024-12-31", "1582-10-15", "0001-01-01", "9999-12-31");
    private static final List<String> TIMESTAMPS =
            List.of(
                    "1970-01-01 00:00:00",
                    "1969-12-31 23:59:59.999999",
                    "2000-02-29 12:34:56.789012",
                    "2024-12-31 23:59:59",
                    "1582-10-15 00:00:00",
                    "0001-01-01 00:00:00",
                    "9999-12-31 23:59:59.999999");

    private final Random random;
    private final int rowsPerTable;
    private final Map<String, Map<Long, String>> rows = new LinkedHashMap<>();
    private long nextRid = 1;

    public DataGen(long seed, int rowsPerTable) {
        this.random = new Random(seed);
        this.rowsPerTable = rowsPerTable;
        for (String table : TABLES) {
            Map<Long, String> tableRows = new LinkedHashMap<>();
            for (int i = 0; i < rowsPerTable; i++) {
                long rid = nextRid++;
                tableRows.put(rid, row(rid));
            }
            rows.put(table, tableRows);
        }
    }

    /** Rendered {@code (v1, v2, ...)} tuples by row id, for building reproductions. */
    public Map<Long, String> rows(String table) {
        return rows.get(table);
    }

    public static String columnsDdl() {
        return COLUMNS.stream()
                .map(c -> c.name() + " " + c.type().sql())
                .collect(Collectors.joining(", "));
    }

    /** The statements that create and populate every table. */
    public List<String> setup() {
        List<String> sql = new ArrayList<>();
        sql.add("CREATE NAMESPACE IF NOT EXISTS " + NAMESPACE);
        sql.add(create("t0", ""));
        sql.add(create("t1", ""));
        sql.add(create("t2", "PARTITIONED BY (bucket(4, k))"));
        sql.add(
                create(
                        "t3",
                        "TBLPROPERTIES ('format-version'='2', 'write.delete.mode'='merge-on-read')"));
        for (String table : TABLES) {
            int batch = table.equals("t1") ? rowsPerTable / 4 : rowsPerTable;
            sql.addAll(inserts(NAMESPACE + "." + table, rows.get(table).values(), Math.max(batch, 1)));
        }
        long firstRid = rows.get("t3").keySet().iterator().next();
        sql.add("DELETE FROM " + NAMESPACE + ".t3 WHERE pmod(rid - " + firstRid + ", 5) = 0");
        rows.get("t3").keySet().removeIf(rid -> Math.floorMod(rid - firstRid, 5L) == 0);
        return sql;
    }

    private static String create(String table, String suffix) {
        return ("CREATE TABLE " + NAMESPACE + "." + table + " (" + columnsDdl() + ") USING iceberg " + suffix)
                .trim();
    }

    /** One INSERT per batch, so each batch lands in its own data files. */
    public static List<String> inserts(String table, java.util.Collection<String> tuples, int batch) {
        List<String> all = new ArrayList<>(tuples);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += batch) {
            List<String> part = all.subList(i, Math.min(i + batch, all.size()));
            out.add("INSERT INTO " + table + " VALUES " + String.join(", ", part));
        }
        return out;
    }

    private String row(long rid) {
        return "("
                + rid
                + ", "
                + value(SqlType.INT)
                + ", "
                + value(SqlType.BIGINT)
                + ", "
                + value(SqlType.DOUBLE)
                + ", "
                + value(SqlType.DEC18)
                + ", "
                + value(SqlType.DEC38)
                + ", "
                + value(SqlType.STRING)
                + ", "
                + value(SqlType.DATE)
                + ", "
                + value(SqlType.TIMESTAMP)
                + ", "
                + value(SqlType.BOOLEAN)
                + ")";
    }

    private String value(SqlType type) {
        return random.nextInt(100) < 12 ? "CAST(NULL AS " + type.sql() + ")" : literal(type, random);
    }

    /** A non-null literal: an edge value about a third of the time, otherwise a small-domain one. */
    public static String literal(SqlType type, Random r) {
        boolean edge = r.nextInt(100) < 35;
        return switch (type) {
            case BOOLEAN -> r.nextBoolean() ? "TRUE" : "FALSE";
            case INT -> edge ? pick(INTS, r) : Integer.toString(r.nextInt(16));
            case BIGINT -> "CAST(" + (edge ? pick(LONGS, r) : Integer.toString(r.nextInt(16))) + " AS BIGINT)";
            case DOUBLE -> edge ? pick(DOUBLES, r) : "CAST(" + (r.nextInt(64) / 4.0) + " AS DOUBLE)";
            case DEC18 -> dec(edge ? pick(DEC18S, r) : (r.nextInt(64) / 4.0) + "000", type);
            case DEC38 -> dec(edge ? pick(DEC38S, r) : (r.nextInt(64) / 4.0) + "000000000", type);
            case STRING -> "'" + (edge ? pick(STRINGS, r) : Character.toString('a' + r.nextInt(5))) + "'";
            case DATE -> "DATE '" + (edge ? pick(DATES, r) : "2024-01-" + day(r)) + "'";
            case TIMESTAMP ->
                    "TIMESTAMP '" + (edge ? pick(TIMESTAMPS, r) : "2024-01-" + day(r) + " 0" + r.nextInt(10) + ":30:00") + "'";
        };
    }

    private static String dec(String text, SqlType type) {
        return "CAST(" + text + " AS " + type.sql() + ")";
    }

    private static String day(Random r) {
        return String.format("%02d", 1 + r.nextInt(16));
    }

    private static String pick(List<String> values, Random r) {
        return values.get(r.nextInt(values.size()));
    }
}
