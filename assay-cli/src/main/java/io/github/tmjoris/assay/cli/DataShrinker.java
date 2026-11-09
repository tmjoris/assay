package io.github.tmjoris.assay.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds a small subset of rows that still reproduces a failure.
 *
 * <p>This is ddmin (delta debugging): repeatedly try dropping a chunk of rows, and halve the chunk
 * size once no chunk can go.
 */
final class DataShrinker {

    private final int budget;
    private int tests;

    DataShrinker(int budget) {
        this.budget = budget;
    }

    int tests() {
        return tests;
    }

    <T> List<T> shrink(List<T> items, Predicate<List<T>> stillFails) {
        List<T> current = new ArrayList<>(items);
        int chunk = Math.max(current.size() / 2, 1);
        while (!current.isEmpty()) {
            boolean removed = false;
            for (int start = 0; start < current.size(); ) {
                if (tests >= budget) {
                    return current;
                }
                List<T> candidate = new ArrayList<>(current.subList(0, start));
                candidate.addAll(current.subList(Math.min(start + chunk, current.size()), current.size()));
                tests++;
                if (stillFails.test(candidate)) {
                    current = candidate;
                    removed = true;
                } else {
                    start += chunk;
                }
            }
            if (chunk == 1 && !removed) {
                return current;
            }
            if (!removed) {
                chunk = Math.max(chunk / 2, 1);
            }
        }
        return current;
    }
}
