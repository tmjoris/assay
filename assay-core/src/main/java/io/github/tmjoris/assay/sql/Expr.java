package io.github.tmjoris.assay.sql;

import java.util.List;
import java.util.stream.Collectors;

/** A typed SQL expression. */
public sealed interface Expr {

    SqlType type();

    String sql();

    /** Direct sub-expressions, in an order {@link #withChildren} accepts back. */
    List<Expr> children();

    Expr withChildren(List<Expr> children);

    record Col(String table, String name, SqlType type) implements Expr {
        @Override
        public String sql() {
            return table + "." + name;
        }

        @Override
        public List<Expr> children() {
            return List.of();
        }

        @Override
        public Expr withChildren(List<Expr> children) {
            return this;
        }
    }

    record Lit(SqlType type, String text) implements Expr {
        @Override
        public String sql() {
            return text;
        }

        @Override
        public List<Expr> children() {
            return List.of();
        }

        @Override
        public Expr withChildren(List<Expr> children) {
            return this;
        }
    }

    record Call(Template template, List<Expr> args) implements Expr {
        @Override
        public SqlType type() {
            return template.result();
        }

        @Override
        public String sql() {
            return template.render(args.stream().map(Expr::sql).toList());
        }

        @Override
        public List<Expr> children() {
            return args;
        }

        @Override
        public Expr withChildren(List<Expr> children) {
            return new Call(template, List.copyOf(children));
        }
    }

    /**
     * A window function. {@code order} must end in columns that make each row unique within its
     * partition, otherwise engines may legitimately number tied rows differently.
     */
    record Window(Call fn, List<Expr> partition, List<Expr> order, boolean slidingFrame)
            implements Expr {
        @Override
        public SqlType type() {
            return fn.type();
        }

        @Override
        public String sql() {
            String p = partition.stream().map(Expr::sql).collect(Collectors.joining(", "));
            String o = order.stream().map(Expr::sql).collect(Collectors.joining(", "));
            String frame = slidingFrame ? " ROWS BETWEEN 1 PRECEDING AND CURRENT ROW" : "";
            return fn.sql() + " OVER (PARTITION BY " + p + " ORDER BY " + o + frame + ")";
        }

        @Override
        public List<Expr> children() {
            return fn.args();
        }

        @Override
        public Expr withChildren(List<Expr> children) {
            return new Window(new Call(fn.template(), List.copyOf(children)), partition, order, slidingFrame);
        }
    }

    /** {@code value [NOT] IN (subquery)}. NOT IN is a null-aware anti join, a classic bug area. */
    record InSubquery(Expr value, Select query, boolean negated) implements Expr {
        @Override
        public SqlType type() {
            return SqlType.BOOLEAN;
        }

        @Override
        public String sql() {
            return "(" + value.sql() + (negated ? " NOT IN (" : " IN (") + query.sql() + "))";
        }

        @Override
        public List<Expr> children() {
            return List.of(value);
        }

        @Override
        public Expr withChildren(List<Expr> children) {
            return new InSubquery(children.get(0), query, negated);
        }
    }

    /** {@code [NOT] EXISTS (subquery)}, whose subquery may refer to outer columns. */
    record Exists(Select query, boolean negated) implements Expr {
        @Override
        public SqlType type() {
            return SqlType.BOOLEAN;
        }

        @Override
        public String sql() {
            return (negated ? "(NOT EXISTS (" : "(EXISTS (") + query.sql() + "))";
        }

        @Override
        public List<Expr> children() {
            return List.of();
        }

        @Override
        public Expr withChildren(List<Expr> children) {
            return this;
        }
    }
}
