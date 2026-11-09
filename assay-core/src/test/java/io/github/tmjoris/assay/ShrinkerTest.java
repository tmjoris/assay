package io.github.tmjoris.assay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tmjoris.assay.gen.QueryGen;
import io.github.tmjoris.assay.shrink.Reductions;
import io.github.tmjoris.assay.shrink.Shrinker;
import io.github.tmjoris.assay.sql.Query;
import org.junit.jupiter.api.Test;

class ShrinkerTest {

    /** Stands in for an engine bug that any query using this function triggers. */
    private static final String BUG = "reverse(";

    private static Query queryContaining(String text) {
        QueryGen gen = new QueryGen(3);
        for (int i = 0; i < 100_000; i++) {
            Query q = gen.next();
            if (q.sql().contains(text) && q.sql().contains(" JOIN ")) {
                return q;
            }
        }
        throw new AssertionError("generator never produced " + text);
    }

    @Test
    void shrinksToTheTriggerAndKeepsItFailing() {
        Query big = queryContaining(BUG);
        Shrinker.Result r = new Shrinker(100_000).shrink(big, q -> q.sql().contains(BUG));
        assertTrue(r.query().sql().contains(BUG), r.query().sql());
        assertFalse(r.query().sql().contains(" JOIN "), r.query().sql());
        assertFalse(r.budgetExhausted());
        assertTrue(r.query().sql().length() < big.sql().length() / 2, r.query().sql());
    }

    @Test
    void budgetIsARealLimit() {
        Query big = queryContaining(BUG);
        Shrinker.Result r = new Shrinker(3).shrink(big, q -> q.sql().contains(BUG));
        assertEquals(3, r.tests());
    }

    @Test
    void aQueryNothingCanReduceIsReturnedAsIs() {
        Query big = queryContaining(BUG);
        Shrinker.Result r = new Shrinker(100_000).shrink(big, q -> q == big);
        assertEquals(big, r.query());
    }

    @Test
    void everyGeneratedQueryHasReductions() {
        QueryGen gen = new QueryGen(9);
        for (int i = 0; i < 500; i++) {
            assertFalse(Reductions.of(gen.next()).isEmpty());
        }
    }
}
