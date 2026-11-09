package io.github.tmjoris.assay.gen;

import io.github.tmjoris.assay.sql.Catalog;
import io.github.tmjoris.assay.sql.Expr;
import io.github.tmjoris.assay.sql.From;
import io.github.tmjoris.assay.sql.Query;
import io.github.tmjoris.assay.sql.Select;
import io.github.tmjoris.assay.sql.SqlType;
import io.github.tmjoris.assay.sql.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates well-typed, deterministic queries from a seed.
 *
 * <p>The same seed always gives the same query, so any finding can be regenerated from its seed
 * alone. Every query is built so that its correct answer is unique: nothing in it lets two correct
 * engines return different rows.
 */
public final class QueryGen {

    private final Random r;
    private int nextAlias;

    public QueryGen(long seed) {
        this.r = new Random(seed);
    }

    /** What the expressions of one SELECT can refer to. */
    private record Scope(List<Expr.Col> columns, List<Expr.Col> rids, List<String> aliases) {}

    private record Source(From from, Scope scope) {}

    public Query next() {
        nextAlias = 0;
        int shape = r.nextInt(100);
        if (shape < 8) {
            return setOp();
        }
        if (shape < 30) {
            return aggregate(1);
        }
        if (shape < 42) {
            return window(1);
        }
        return plain(1);
    }

    private Query setOp() {
        List<SqlType> types = randomTypes(1 + r.nextInt(3));
        Select left = projection(types);
        Select right = projection(types);
        String op = List.of("UNION ALL", "UNION", "INTERSECT", "EXCEPT").get(r.nextInt(4));
        return new Query.SetOp(left, op, right);
    }

    private Select projection(List<SqlType> types) {
        Source src = table();
        List<Select.Item> items = new ArrayList<>();
        for (SqlType t : types) {
            items.add(new Select.Item(expr(t, src.scope(), 2), "c" + items.size()));
        }
        return new Select(false, null, items, src.from(), maybeWhere(src.scope()), List.of(), null, false, null);
    }

    private Select plain(int depth) {
        Source src = source(depth);
        List<Select.Item> items = new ArrayList<>();
        int n = 1 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            items.add(new Select.Item(expr(randomType(), src.scope(), 3), "c" + i));
        }
        boolean order = r.nextInt(100) < 25;
        Integer limit = order && r.nextBoolean() ? 1 + r.nextInt(20) : null;
        return new Select(
                r.nextInt(100) < 15,
                joinHint(src),
                items,
                src.from(),
                maybeWhere(src.scope()),
                List.of(),
                null,
                order,
                limit);
    }

    private Select aggregate(int depth) {
        Source src = source(depth);
        List<Select.Item> items = new ArrayList<>();
        List<Expr> groups = new ArrayList<>();
        int groupCount = r.nextInt(3);
        for (int i = 0; i < groupCount; i++) {
            Expr g = expr(randomType(), src.scope(), 1);
            groups.add(g);
            items.add(new Select.Item(g, "c" + items.size()));
        }
        int aggCount = 1 + r.nextInt(3);
        boolean floating = false;
        Expr firstAgg = null;
        for (int i = 0; i < aggCount; i++) {
            Expr a = aggregateCall(src.scope());
            floating |= a.type() == SqlType.DOUBLE;
            if (firstAgg == null) {
                firstAgg = a;
            }
            items.add(new Select.Item(a, "c" + items.size()));
        }
        Expr having = null;
        if (r.nextInt(100) < 20 && firstAgg != null) {
            Template gt =
                    Catalog.scalars(SqlType.BOOLEAN).stream()
                            .filter(t -> t.pattern().equals("({0} IS NOT NULL)") && t.args().get(0) == SqlType.BIGINT)
                            .findFirst()
                            .orElseThrow();
            Expr count = new Expr.Call(Catalog.aggregates().get(0), List.of());
            having = new Expr.Call(gt, List.of(count));
        }
        // Summing doubles depends on the order rows arrive in, so totals may differ in the last
        // bits. Ordering or limiting by such a total would turn that harmless noise into a
        // different row set.
        boolean order = !floating && r.nextInt(100) < 25;
        Integer limit = order && r.nextBoolean() ? 1 + r.nextInt(20) : null;
        return new Select(
                false, joinHint(src), items, src.from(), maybeWhere(src.scope()), groups, having, order, limit);
    }

    private Select window(int depth) {
        Source src = source(depth);
        List<Select.Item> items = new ArrayList<>();
        int n = 1 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            items.add(new Select.Item(windowCall(src.scope()), "c" + i));
        }
        items.add(new Select.Item(expr(randomType(), src.scope(), 2), "c" + n));
        return new Select(
                false, joinHint(src), items, src.from(), maybeWhere(src.scope()), List.of(), null, false, null);
    }

    private Source source(int depth) {
        int pick = r.nextInt(100);
        if (depth > 0 && pick < 45) {
            return join(depth);
        }
        if (depth > 0 && pick < 60) {
            return derived();
        }
        return table();
    }

    private Source table() {
        String name = DataGen.TABLES.get(r.nextInt(DataGen.TABLES.size()));
        String alias = "a" + nextAlias++;
        List<Expr.Col> cols =
                DataGen.COLUMNS.stream().map(c -> new Expr.Col(alias, c.name(), c.type())).toList();
        Expr.Col rid = cols.get(0);
        return new Source(
                new From.Table(DataGen.NAMESPACE + "." + name, alias),
                new Scope(cols, List.of(rid), List.of(alias)));
    }

    /** A filtered subquery in FROM, exposing the columns of its table plus its unique row id. */
    private Source derived() {
        Source inner = table();
        String alias = "a" + nextAlias++;
        List<Select.Item> items = new ArrayList<>();
        List<Expr.Col> outer = new ArrayList<>();
        for (Expr.Col c : inner.scope().columns()) {
            boolean keep = c.name().equals("rid") || r.nextInt(100) < 70;
            if (keep) {
                items.add(new Select.Item(c, c.name()));
                outer.add(new Expr.Col(alias, c.name(), c.type()));
            }
        }
        Select q =
                new Select(false, null, items, inner.from(), maybeWhere(inner.scope()), List.of(), null, false, null);
        return new Source(
                new From.Derived(q, alias),
                new Scope(outer, List.of(outer.get(0)), List.of(alias)));
    }

    private Source join(int depth) {
        Source left = r.nextInt(100) < 25 ? derived() : table();
        Source right = r.nextInt(100) < 25 ? derived() : table();
        String kind = List.of("INNER", "LEFT", "RIGHT", "FULL", "LEFT SEMI", "LEFT ANTI", "CROSS").get(weightedJoin());
        List<Expr.Col> visible = new ArrayList<>(left.scope().columns());
        List<Expr.Col> rids = new ArrayList<>(left.scope().rids());
        List<String> aliases = new ArrayList<>(left.scope().aliases());
        boolean rightVisible = !kind.equals("LEFT SEMI") && !kind.equals("LEFT ANTI");
        if (rightVisible) {
            visible.addAll(right.scope().columns());
            rids.addAll(right.scope().rids());
            aliases.addAll(right.scope().aliases());
        }
        Expr on = null;
        if (!kind.equals("CROSS")) {
            on = equiKey(left.scope(), right.scope());
            if (r.nextInt(100) < 30) {
                Scope both = new Scope(concat(left.scope().columns(), right.scope().columns()), rids, aliases);
                on = and(on, expr(SqlType.BOOLEAN, both, 2));
            }
        }
        return new Source(new From.Join(kind, left.from(), right.from(), on), new Scope(visible, rids, aliases));
    }

    private int weightedJoin() {
        int p = r.nextInt(100);
        if (p < 35) {
            return 0;
        }
        if (p < 50) {
            return 1;
        }
        if (p < 60) {
            return 2;
        }
        if (p < 72) {
            return 3;
        }
        if (p < 84) {
            return 4;
        }
        if (p < 96) {
            return 5;
        }
        return 6;
    }

    private Expr equiKey(Scope left, Scope right) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Expr.Col a = left.columns().get(r.nextInt(left.columns().size()));
            List<Expr.Col> same = right.columns().stream().filter(c -> c.type() == a.type()).toList();
            if (!same.isEmpty() && !a.name().equals("rid")) {
                Expr.Col b = same.get(r.nextInt(same.size()));
                String op = r.nextInt(100) < 85 ? "({0} = {1})" : "({0} <=> {1})";
                return new Expr.Call(comparison(op, a.type()), List.of(a, b));
            }
        }
        Expr.Col a = left.columns().get(0);
        Expr.Col b = right.columns().get(0);
        return new Expr.Call(comparison("({0} = {1})", a.type()), List.of(a, b));
    }

    private static Template comparison(String pattern, SqlType type) {
        return Catalog.scalars(SqlType.BOOLEAN).stream()
                .filter(t -> t.pattern().equals(pattern) && t.args().get(0) == type)
                .findFirst()
                .orElseThrow();
    }

    private Expr and(Expr a, Expr b) {
        Template and =
                Catalog.scalars(SqlType.BOOLEAN).stream()
                        .filter(t -> t.pattern().equals("({0} AND {1})"))
                        .findFirst()
                        .orElseThrow();
        return new Expr.Call(and, List.of(a, b));
    }

    private String joinHint(Source src) {
        if (!(src.from() instanceof From.Join j) || r.nextInt(100) < 50) {
            return null;
        }
        String target = lastAlias(j.right());
        return List.of("BROADCAST", "MERGE", "SHUFFLE_HASH").get(r.nextInt(3)) + "(" + target + ")";
    }

    private static String lastAlias(From from) {
        return switch (from) {
            case From.Table t -> t.alias();
            case From.Derived d -> d.alias();
            case From.Join j -> lastAlias(j.right());
        };
    }

    private Expr maybeWhere(Scope scope) {
        int p = r.nextInt(100);
        if (p < 45) {
            return null;
        }
        if (p < 60) {
            return subqueryPredicate(scope);
        }
        return expr(SqlType.BOOLEAN, scope, 3);
    }

    private Expr subqueryPredicate(Scope scope) {
        Source inner = table();
        if (r.nextBoolean()) {
            Expr.Col outer = scope.columns().get(r.nextInt(scope.columns().size()));
            Expr.Col key =
                    inner.scope().columns().stream()
                            .filter(c -> c.type() == outer.type())
                            .findFirst()
                            .orElseThrow();
            Select q =
                    new Select(
                            false,
                            null,
                            List.of(new Select.Item(key, "c0")),
                            inner.from(),
                            maybeSimpleWhere(inner.scope()),
                            List.of(),
                            null,
                            false,
                            null);
            return new Expr.InSubquery(outer, q, r.nextBoolean());
        }
        Expr.Col outer = scope.columns().stream().filter(c -> c.type() == SqlType.INT).findFirst().orElse(null);
        Expr.Col key = inner.scope().columns().stream().filter(c -> c.type() == SqlType.INT).findFirst().orElseThrow();
        Expr where =
                outer == null
                        ? expr(SqlType.BOOLEAN, inner.scope(), 2)
                        : new Expr.Call(comparison("({0} = {1})", SqlType.INT), List.of(key, outer));
        Select q =
                new Select(
                        false,
                        null,
                        List.of(new Select.Item(new Expr.Lit(SqlType.INT, "1"), "c0")),
                        inner.from(),
                        where,
                        List.of(),
                        null,
                        false,
                        null);
        return new Expr.Exists(q, r.nextBoolean());
    }

    private Expr maybeSimpleWhere(Scope scope) {
        return r.nextBoolean() ? null : expr(SqlType.BOOLEAN, scope, 1);
    }

    private Expr aggregateCall(Scope scope) {
        List<Template> all = Catalog.aggregates();
        Template t = all.get(r.nextInt(all.size()));
        List<Expr> args = new ArrayList<>();
        for (SqlType a : t.args()) {
            args.add(expr(a, scope, 2));
        }
        return new Expr.Call(t, args);
    }

    private Expr windowCall(Scope scope) {
        List<Template> all = Catalog.windows();
        Template t = all.get(r.nextInt(all.size()));
        List<Expr> args = new ArrayList<>();
        for (SqlType a : t.args()) {
            args.add(expr(a, scope, 1));
        }
        List<Expr> partition = List.of(expr(randomKeyType(), scope, 1));
        List<Expr> order = new ArrayList<>();
        if (r.nextBoolean()) {
            order.add(expr(randomKeyType(), scope, 1));
        }
        order.addAll(scope.rids());
        boolean frameAllowed = t.pattern().startsWith("sum") || t.pattern().startsWith("count")
                || t.pattern().startsWith("min") || t.pattern().startsWith("max");
        return new Expr.Window(new Expr.Call(t, args), partition, order, frameAllowed && r.nextBoolean());
    }

    /** Generates an expression of exactly the requested type, at most {@code depth} calls deep. */
    Expr expr(SqlType type, Scope scope, int depth) {
        if (depth > 0 && r.nextInt(100) < 70) {
            List<Template> candidates = Catalog.scalars(type);
            if (!candidates.isEmpty()) {
                Template t = candidates.get(r.nextInt(candidates.size()));
                List<Expr> args = new ArrayList<>();
                for (SqlType a : t.args()) {
                    args.add(expr(a, scope, depth - 1));
                }
                return new Expr.Call(t, args);
            }
        }
        List<Expr.Col> matching = scope.columns().stream().filter(c -> c.type() == type).toList();
        if (!matching.isEmpty() && r.nextInt(100) < 80) {
            return matching.get(r.nextInt(matching.size()));
        }
        return literal(type);
    }

    private Expr literal(SqlType type) {
        if (r.nextInt(100) < 10) {
            return new Expr.Lit(type, "CAST(NULL AS " + type.sql() + ")");
        }
        return new Expr.Lit(type, DataGen.literal(type, r));
    }

    private SqlType randomType() {
        SqlType[] all = SqlType.values();
        return all[r.nextInt(all.length)];
    }

    private SqlType randomKeyType() {
        List<SqlType> keys = List.of(SqlType.INT, SqlType.BIGINT, SqlType.STRING, SqlType.DATE, SqlType.BOOLEAN);
        return keys.get(r.nextInt(keys.size()));
    }

    private List<SqlType> randomTypes(int n) {
        List<SqlType> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(randomType());
        }
        return out;
    }

    private static List<Expr.Col> concat(List<Expr.Col> a, List<Expr.Col> b) {
        List<Expr.Col> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
