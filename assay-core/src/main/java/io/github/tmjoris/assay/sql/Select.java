package io.github.tmjoris.assay.sql;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A SELECT block. Optional clauses are null or empty when absent.
 *
 * <p>{@code orderAll} sorts by every output column. That is the only ordering the generator uses,
 * because it makes both the row order and any LIMIT well defined: two rows that tie are identical.
 *
 * @param hint a join strategy hint body such as {@code MERGE(a1)}, forcing a particular join
 *     operator so each engine's sort-merge, hash and broadcast paths all get exercised
 */
public record Select(
        boolean distinct,
        String hint,
        List<Item> items,
        From from,
        Expr where,
        List<Expr> groupBy,
        Expr having,
        boolean orderAll,
        Integer limit)
        implements Query {

    public record Item(Expr expr, String alias) {
        public String sql() {
            return expr.sql() + " AS " + alias;
        }
    }

    @Override
    public boolean ordered() {
        return orderAll;
    }

    @Override
    public String sql() {
        StringBuilder b = new StringBuilder("SELECT ");
        if (hint != null) {
            b.append("/*+ ").append(hint).append(" */ ");
        }
        if (distinct) {
            b.append("DISTINCT ");
        }
        b.append(items.stream().map(Item::sql).collect(Collectors.joining(", ")));
        b.append(" FROM ").append(from.sql());
        if (where != null) {
            b.append(" WHERE ").append(where.sql());
        }
        if (!groupBy.isEmpty()) {
            b.append(" GROUP BY ")
                    .append(groupBy.stream().map(Expr::sql).collect(Collectors.joining(", ")));
        }
        if (having != null) {
            b.append(" HAVING ").append(having.sql());
        }
        if (orderAll) {
            String ordinals =
                    java.util.stream.IntStream.rangeClosed(1, items.size())
                            .mapToObj(Integer::toString)
                            .collect(Collectors.joining(", "));
            b.append(" ORDER BY ").append(ordinals);
        }
        if (limit != null) {
            b.append(" LIMIT ").append(limit);
        }
        return b.toString();
    }
}
