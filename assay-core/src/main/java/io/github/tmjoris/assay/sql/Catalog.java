package io.github.tmjoris.assay.sql;

import static io.github.tmjoris.assay.sql.SqlType.BIGINT;
import static io.github.tmjoris.assay.sql.SqlType.BOOLEAN;
import static io.github.tmjoris.assay.sql.SqlType.DATE;
import static io.github.tmjoris.assay.sql.SqlType.DEC18;
import static io.github.tmjoris.assay.sql.SqlType.DEC38;
import static io.github.tmjoris.assay.sql.SqlType.DOUBLE;
import static io.github.tmjoris.assay.sql.SqlType.INT;
import static io.github.tmjoris.assay.sql.SqlType.STRING;
import static io.github.tmjoris.assay.sql.SqlType.TIMESTAMP;

import io.github.tmjoris.assay.sql.Template.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The functions the fuzzer may call.
 *
 * <p>Only deterministic functions belong here. {@code rand()}, {@code first()} or {@code
 * collect_list()} can legitimately differ between engines, so a difference would prove nothing.
 * Decimal arithmetic is cast back to a fixed type because Spark widens the result type per
 * operator, and the generator needs to know what type each expression returns.
 */
public final class Catalog {

    private static final List<Template> ALL = build();
    private static final Map<SqlType, List<Template>> SCALARS =
            ALL.stream()
                    .filter(t -> t.kind() == Kind.SCALAR)
                    .collect(Collectors.groupingBy(Template::result));

    private Catalog() {}

    public static List<Template> scalars(SqlType result) {
        return SCALARS.getOrDefault(result, List.of());
    }

    public static List<Template> aggregates() {
        return ALL.stream().filter(t -> t.kind() == Kind.AGGREGATE).toList();
    }

    public static List<Template> windows() {
        return ALL.stream().filter(t -> t.kind() == Kind.WINDOW).toList();
    }

    public static int size() {
        return ALL.size();
    }

    private static Template s(String pattern, SqlType result, SqlType... args) {
        return new Template(pattern, result, List.of(args), Kind.SCALAR);
    }

    private static Template a(String pattern, SqlType result, SqlType... args) {
        return new Template(pattern, result, List.of(args), Kind.AGGREGATE);
    }

    private static Template w(String pattern, SqlType result, SqlType... args) {
        return new Template(pattern, result, List.of(args), Kind.WINDOW);
    }

    private static List<Template> build() {
        List<Template> t = new ArrayList<>();
        SqlType[] numerics = {INT, BIGINT, DOUBLE};
        SqlType[] comparable = {INT, BIGINT, DOUBLE, DEC18, DEC38, STRING, DATE, TIMESTAMP, BOOLEAN};

        for (SqlType n : numerics) {
            for (String op : List.of("+", "-", "*", "%")) {
                t.add(s("({0} " + op + " {1})", n, n, n));
            }
            t.add(s("abs({0})", n, n));
            t.add(s("(- {0})", n, n));
            t.add(s("pmod({0}, {1})", n, n, n));
            t.add(s("greatest({0}, {1})", n, n, n));
            t.add(s("least({0}, {1})", n, n, n));
        }
        t.add(s("({0} / {1})", DOUBLE, DOUBLE, DOUBLE));
        t.add(s("({0} div {1})", BIGINT, BIGINT, BIGINT));
        t.add(s("({0} & {1})", BIGINT, BIGINT, BIGINT));
        t.add(s("({0} | {1})", BIGINT, BIGINT, BIGINT));
        t.add(s("({0} ^ {1})", BIGINT, BIGINT, BIGINT));
        t.add(s("(~ {0})", BIGINT, BIGINT));
        t.add(s("shiftleft({0}, pmod({1}, 64))", BIGINT, BIGINT, INT));
        t.add(s("shiftright({0}, pmod({1}, 64))", BIGINT, BIGINT, INT));

        for (String fn : List.of("sqrt", "exp", "ln", "log10", "sin", "cos", "atan", "sign", "cbrt")) {
            t.add(s(fn + "({0})", DOUBLE, DOUBLE));
        }
        t.add(s("power({0}, {1})", DOUBLE, DOUBLE, DOUBLE));
        t.add(s("atan2({0}, {1})", DOUBLE, DOUBLE, DOUBLE));
        t.add(s("nanvl({0}, {1})", DOUBLE, DOUBLE, DOUBLE));
        t.add(s("round({0}, pmod({1}, 6))", DOUBLE, DOUBLE, INT));
        t.add(s("bround({0}, pmod({1}, 6))", DOUBLE, DOUBLE, INT));
        t.add(s("floor({0})", BIGINT, DOUBLE));
        t.add(s("ceil({0})", BIGINT, DOUBLE));

        for (SqlType d : List.of(DEC18, DEC38)) {
            String ty = d.sql();
            for (String op : List.of("+", "-", "*", "/", "%")) {
                t.add(s("CAST(({0} " + op + " {1}) AS " + ty + ")", d, d, d));
            }
            t.add(s("CAST(abs({0}) AS " + ty + ")", d, d));
            t.add(s("CAST((- {0}) AS " + ty + ")", d, d));
            t.add(s("CAST(round({0}, pmod({1}, 6)) AS " + ty + ")", d, d, INT));
            t.add(s("CAST(floor({0}) AS " + ty + ")", d, d));
            t.add(s("CAST(ceil({0}) AS " + ty + ")", d, d));
        }

        t.add(s("upper({0})", STRING, STRING));
        t.add(s("lower({0})", STRING, STRING));
        t.add(s("initcap({0})", STRING, STRING));
        t.add(s("trim({0})", STRING, STRING));
        t.add(s("ltrim({0})", STRING, STRING));
        t.add(s("rtrim({0})", STRING, STRING));
        t.add(s("reverse({0})", STRING, STRING));
        t.add(s("concat({0}, {1})", STRING, STRING, STRING));
        t.add(s("concat_ws({0}, {1}, {2})", STRING, STRING, STRING, STRING));
        t.add(s("substring({0}, {1}, {2})", STRING, STRING, INT, INT));
        t.add(s("replace({0}, {1}, {2})", STRING, STRING, STRING, STRING));
        t.add(s("translate({0}, {1}, {2})", STRING, STRING, STRING, STRING));
        t.add(s("repeat({0}, pmod({1}, 4))", STRING, STRING, INT));
        t.add(s("lpad({0}, pmod({1}, 20), {2})", STRING, STRING, INT, STRING));
        t.add(s("rpad({0}, pmod({1}, 20), {2})", STRING, STRING, INT, STRING));
        t.add(s("hex({0})", STRING, BIGINT));
        t.add(s("md5({0})", STRING, STRING));
        t.add(s("sha2({0}, 256)", STRING, STRING));
        t.add(s("date_format({0}, 'yyyy-MM-dd HH:mm:ss')", STRING, TIMESTAMP));
        t.add(s("length({0})", INT, STRING));
        t.add(s("ascii({0})", INT, STRING));
        t.add(s("instr({0}, {1})", INT, STRING, STRING));
        t.add(s("locate({0}, {1}, {2})", INT, STRING, STRING, INT));

        t.add(s("year({0})", INT, DATE));
        t.add(s("month({0})", INT, DATE));
        t.add(s("day({0})", INT, DATE));
        t.add(s("quarter({0})", INT, DATE));
        t.add(s("dayofweek({0})", INT, DATE));
        t.add(s("dayofyear({0})", INT, DATE));
        t.add(s("weekofyear({0})", INT, DATE));
        t.add(s("datediff({0}, {1})", INT, DATE, DATE));
        t.add(s("hour({0})", INT, TIMESTAMP));
        t.add(s("minute({0})", INT, TIMESTAMP));
        t.add(s("second({0})", INT, TIMESTAMP));
        t.add(s("date_add({0}, pmod({1}, 4000))", DATE, DATE, INT));
        t.add(s("date_sub({0}, pmod({1}, 4000))", DATE, DATE, INT));
        t.add(s("add_months({0}, pmod({1}, 400))", DATE, DATE, INT));
        t.add(s("last_day({0})", DATE, DATE));
        t.add(s("trunc({0}, 'MM')", DATE, DATE));
        t.add(s("trunc({0}, 'YEAR')", DATE, DATE));
        t.add(s("to_date({0})", DATE, TIMESTAMP));
        for (String unit : List.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "WEEK")) {
            t.add(s("date_trunc('" + unit + "', {0})", TIMESTAMP, TIMESTAMP));
        }

        t.add(s("hash({0})", INT, BIGINT));
        t.add(s("hash({0})", INT, STRING));
        t.add(s("hash({0})", INT, DOUBLE));
        t.add(s("xxhash64({0})", BIGINT, BIGINT));
        t.add(s("xxhash64({0})", BIGINT, STRING));

        SqlType[] all = SqlType.values();
        for (SqlType from : all) {
            for (SqlType to : all) {
                if (from != to && castable(from, to)) {
                    t.add(s("CAST({0} AS " + to.sql() + ")", to, from));
                }
            }
        }

        for (SqlType c : comparable) {
            for (String op : List.of("=", "<=>", "!=", "<", "<=", ">", ">=")) {
                t.add(s("({0} " + op + " {1})", BOOLEAN, c, c));
            }
            t.add(s("({0} IS NULL)", BOOLEAN, c));
            t.add(s("({0} IS NOT NULL)", BOOLEAN, c));
            t.add(s("({0} BETWEEN {1} AND {2})", BOOLEAN, c, c, c));
            t.add(s("({0} IN ({1}, {2}, {3}))", BOOLEAN, c, c, c, c));
        }
        t.add(s("({0} AND {1})", BOOLEAN, BOOLEAN, BOOLEAN));
        t.add(s("({0} OR {1})", BOOLEAN, BOOLEAN, BOOLEAN));
        t.add(s("(NOT {0})", BOOLEAN, BOOLEAN));
        t.add(s("isnan({0})", BOOLEAN, DOUBLE));
        t.add(s("({0} LIKE {1})", BOOLEAN, STRING, STRING));
        t.add(s("startswith({0}, {1})", BOOLEAN, STRING, STRING));
        t.add(s("endswith({0}, {1})", BOOLEAN, STRING, STRING));
        t.add(s("contains({0}, {1})", BOOLEAN, STRING, STRING));

        for (SqlType c : all) {
            t.add(s("CASE WHEN {0} THEN {1} ELSE {2} END", c, BOOLEAN, c, c));
            t.add(s("if({0}, {1}, {2})", c, BOOLEAN, c, c));
            t.add(s("coalesce({0}, {1})", c, c, c));
            t.add(s("nullif({0}, {1})", c, c, c));
        }

        t.add(a("count(1)", BIGINT));
        for (SqlType c : comparable) {
            t.add(a("count({0})", BIGINT, c));
            t.add(a("count(DISTINCT {0})", BIGINT, c));
            t.add(a("min({0})", c, c));
            t.add(a("max({0})", c, c));
        }
        t.add(a("sum({0})", BIGINT, INT));
        t.add(a("sum({0})", BIGINT, BIGINT));
        t.add(a("sum({0})", DOUBLE, DOUBLE));
        t.add(a("sum(DISTINCT {0})", BIGINT, BIGINT));
        t.add(a("CAST(sum({0}) AS " + DEC38.sql() + ")", DEC38, DEC18));
        t.add(a("CAST(sum({0}) AS " + DEC38.sql() + ")", DEC38, DEC38));
        t.add(a("avg({0})", DOUBLE, BIGINT));
        t.add(a("avg({0})", DOUBLE, DOUBLE));
        t.add(a("CAST(avg({0}) AS " + DEC38.sql() + ")", DEC38, DEC18));
        t.add(a("stddev_pop({0})", DOUBLE, DOUBLE));
        t.add(a("var_samp({0})", DOUBLE, DOUBLE));
        t.add(a("bool_and({0})", BOOLEAN, BOOLEAN));
        t.add(a("bool_or({0})", BOOLEAN, BOOLEAN));
        t.add(a("bit_xor({0})", BIGINT, BIGINT));

        t.add(w("row_number()", INT));
        t.add(w("rank()", INT));
        t.add(w("dense_rank()", INT));
        t.add(w("sum({0})", BIGINT, BIGINT));
        t.add(w("count({0})", BIGINT, STRING));
        t.add(w("min({0})", DATE, DATE));
        t.add(w("max({0})", DEC18, DEC18));
        t.add(w("lag({0})", BIGINT, BIGINT));
        t.add(w("lead({0})", STRING, STRING));
        return List.copyOf(t);
    }

    private static boolean castable(SqlType from, SqlType to) {
        if (from == STRING || to == STRING) {
            return true;
        }
        if (from.isNumeric() && to.isNumeric()) {
            return true;
        }
        if (from == BOOLEAN || to == BOOLEAN) {
            return (from.isNumeric() && from != DOUBLE) || (to.isNumeric() && to != DOUBLE);
        }
        return (from == DATE && to == TIMESTAMP) || (from == TIMESTAMP && to == DATE);
    }
}
