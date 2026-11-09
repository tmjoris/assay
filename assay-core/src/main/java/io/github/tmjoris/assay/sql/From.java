package io.github.tmjoris.assay.sql;

/** The FROM clause of a {@link Select}. */
public sealed interface From {

    String sql();

    record Table(String name, String alias) implements From {
        @Override
        public String sql() {
            return name + " " + alias;
        }
    }

    record Derived(Select query, String alias) implements From {
        @Override
        public String sql() {
            return "(" + query.sql() + ") " + alias;
        }
    }

    /** A join. {@code on} is null only for a cross join. */
    record Join(String kind, From left, From right, Expr on) implements From {
        @Override
        public String sql() {
            String base = left.sql() + " " + kind + " JOIN " + right.sql();
            return on == null ? base : base + " ON " + on.sql();
        }
    }
}
