package io.github.tmjoris.assay.sql;

/** A complete query: one {@link Select}, or two combined with a set operator. */
public sealed interface Query permits Select, Query.SetOp {

    String sql();

    /** True when the row order of the result is part of what the query promises. */
    boolean ordered();

    record SetOp(Select left, String operator, Select right) implements Query {
        @Override
        public String sql() {
            return "(" + left.sql() + ") " + operator + " (" + right.sql() + ")";
        }

        @Override
        public boolean ordered() {
            return false;
        }
    }
}
