package io.github.tmjoris.assay.shrink;

import io.github.tmjoris.assay.sql.Query;
import java.util.function.Predicate;

/**
 * Reduces a failing query to a small one that still fails the same way.
 *
 * <p>Greedy: take the first simpler query that still fails, then start again from it, stopping when
 * no simpler query fails or the budget runs out. The budget matters because every test is a real
 * run on two engines.
 */
public final class Shrinker {

    public record Result(Query query, int tests, boolean budgetExhausted) {}

    private final int budget;

    public Shrinker(int budget) {
        this.budget = budget;
    }

    public Result shrink(Query failing, Predicate<Query> stillFails) {
        Query current = failing;
        int tests = 0;
        boolean improved = true;
        while (improved) {
            improved = false;
            for (Query candidate : Reductions.of(current)) {
                if (Reductions.size(candidate) >= Reductions.size(current)) {
                    continue;
                }
                if (tests >= budget) {
                    return new Result(current, tests, true);
                }
                tests++;
                if (stillFails.test(candidate)) {
                    current = candidate;
                    improved = true;
                    break;
                }
            }
        }
        return new Result(current, tests, false);
    }
}
