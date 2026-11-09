package io.github.tmjoris.assay.shrink;

import io.github.tmjoris.assay.sql.Expr;
import io.github.tmjoris.assay.sql.From;
import io.github.tmjoris.assay.sql.Query;
import io.github.tmjoris.assay.sql.Select;
import io.github.tmjoris.assay.sql.SqlType;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists every query that is one small step simpler than a given query.
 *
 * <p>Some candidates are invalid SQL, for example after removing a join whose columns are still
 * used. That is fine: both engines reject them, so the shrinker discards them. Larger steps come
 * first so it gets small quickly.
 */
public final class Reductions {

    private Reductions() {}

    public static List<Query> of(Query query) {
        return switch (query) {
            case Query.SetOp op -> {
                List<Query> out = new ArrayList<>(List.of(op.left(), op.right()));
                for (Select s : select(op.left())) {
                    out.add(new Query.SetOp(s, op.operator(), op.right()));
                }
                for (Select s : select(op.right())) {
                    out.add(new Query.SetOp(op.left(), op.operator(), s));
                }
                if (!op.operator().equals("UNION ALL")) {
                    out.add(new Query.SetOp(op.left(), "UNION ALL", op.right()));
                }
                yield out;
            }
            case Select s -> new ArrayList<>(select(s));
        };
    }

    private static List<Select> select(Select s) {
        List<Select> out = new ArrayList<>();
        for (From f : from(s.from())) {
            out.add(withFrom(s, f));
        }
        if (s.hint() != null) {
            out.add(new Select(s.distinct(), null, s.items(), s.from(), s.where(), s.groupBy(), s.having(), s.orderAll(), s.limit()));
        }
        if (s.where() != null) {
            out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), null, s.groupBy(), s.having(), s.orderAll(), s.limit()));
        }
        if (s.having() != null) {
            out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), s.where(), s.groupBy(), null, s.orderAll(), s.limit()));
        }
        if (s.limit() != null) {
            out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), s.where(), s.groupBy(), s.having(), s.orderAll(), null));
        }
        if (s.orderAll() && s.limit() == null) {
            out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), s.where(), s.groupBy(), s.having(), false, null));
        }
        if (s.distinct()) {
            out.add(new Select(false, s.hint(), s.items(), s.from(), s.where(), s.groupBy(), s.having(), s.orderAll(), s.limit()));
        }
        for (int i = 0; i < s.groupBy().size(); i++) {
            List<Expr> groups = new ArrayList<>(s.groupBy());
            groups.remove(i);
            out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), s.where(), groups, s.having(), s.orderAll(), s.limit()));
        }
        if (s.items().size() > 1) {
            for (int i = 0; i < s.items().size(); i++) {
                List<Select.Item> items = new ArrayList<>(s.items());
                items.remove(i);
                out.add(new Select(s.distinct(), s.hint(), items, s.from(), s.where(), s.groupBy(), s.having(), s.orderAll(), s.limit()));
            }
        }
        for (int i = 0; i < s.items().size(); i++) {
            Select.Item item = s.items().get(i);
            for (Expr e : expr(item.expr())) {
                List<Select.Item> items = new ArrayList<>(s.items());
                items.set(i, new Select.Item(e, item.alias()));
                out.add(new Select(s.distinct(), s.hint(), items, s.from(), s.where(), s.groupBy(), s.having(), s.orderAll(), s.limit()));
            }
        }
        if (s.where() != null) {
            for (Expr e : expr(s.where())) {
                out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), e, s.groupBy(), s.having(), s.orderAll(), s.limit()));
            }
        }
        if (s.having() != null) {
            for (Expr e : expr(s.having())) {
                out.add(new Select(s.distinct(), s.hint(), s.items(), s.from(), s.where(), s.groupBy(), e, s.orderAll(), s.limit()));
            }
        }
        return out;
    }

    private static Select withFrom(Select s, From f) {
        return new Select(s.distinct(), s.hint(), s.items(), f, s.where(), s.groupBy(), s.having(), s.orderAll(), s.limit());
    }

    private static List<From> from(From f) {
        List<From> out = new ArrayList<>();
        switch (f) {
            case From.Table t -> {}
            case From.Derived d -> {
                for (Select s : select(d.query())) {
                    out.add(new From.Derived(s, d.alias()));
                }
            }
            case From.Join j -> {
                out.add(j.left());
                out.add(j.right());
                if (!j.kind().equals("INNER") && !j.kind().equals("CROSS")) {
                    out.add(new From.Join("INNER", j.left(), j.right(), j.on()));
                }
                for (From l : from(j.left())) {
                    out.add(new From.Join(j.kind(), l, j.right(), j.on()));
                }
                for (From r : from(j.right())) {
                    out.add(new From.Join(j.kind(), j.left(), r, j.on()));
                }
                if (j.on() != null) {
                    for (Expr e : expr(j.on())) {
                        out.add(new From.Join(j.kind(), j.left(), j.right(), e));
                    }
                }
            }
        }
        return out;
    }

    /** Replacements for an expression: a same-typed child, a plain literal, or a simpler child. */
    static List<Expr> expr(Expr e) {
        List<Expr> out = new ArrayList<>();
        if (e instanceof Expr.Lit) {
            Expr simplest = simplest(e.type());
            if (!e.equals(simplest)) {
                out.add(simplest);
            }
            return out;
        }
        for (Expr child : e.children()) {
            if (child.type() == e.type()) {
                out.add(child);
            }
        }
        if (!(e instanceof Expr.Col)) {
            out.add(simplest(e.type()));
        }
        if (e instanceof Expr.InSubquery in) {
            for (Select s : select(in.query())) {
                out.add(new Expr.InSubquery(in.value(), s, in.negated()));
            }
        }
        if (e instanceof Expr.Exists ex) {
            for (Select s : select(ex.query())) {
                out.add(new Expr.Exists(s, ex.negated()));
            }
        }
        List<Expr> children = e.children();
        for (int i = 0; i < children.size(); i++) {
            for (Expr simpler : expr(children.get(i))) {
                List<Expr> next = new ArrayList<>(children);
                next.set(i, simpler);
                out.add(e.withChildren(next));
            }
        }
        return out;
    }

    private static Expr simplest(SqlType type) {
        return new Expr.Lit(
                type,
                switch (type) {
                    case BOOLEAN -> "TRUE";
                    case INT -> "0";
                    case BIGINT -> "CAST(0 AS BIGINT)";
                    case DOUBLE -> "CAST(0.0 AS DOUBLE)";
                    case DEC18, DEC38 -> "CAST(0 AS " + type.sql() + ")";
                    case STRING -> "'a'";
                    case DATE -> "DATE '2024-01-01'";
                    case TIMESTAMP -> "TIMESTAMP '2024-01-01 00:00:00'";
                });
    }

    /** Roughly how big a query is. A step only counts as progress if this goes down. */
    public static int size(Query q) {
        return q.sql().length();
    }
}
