package io.github.tmjoris.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LanePoolTest {

    private static List<AtomicBoolean> lanes(int n) {
        return IntStream.range(0, n).mapToObj(i -> new AtomicBoolean()).toList();
    }

    private static List<Integer> items(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void aLaneIsNeverUsedByTwoTasksAtOnce() throws Exception {
        List<AtomicBoolean> lanes = lanes(4);
        AtomicInteger shared = new AtomicInteger();
        new LanePool<>(lanes)
                .runAll(
                        items(400).iterator(),
                        (lane, item) -> {
                            if (!lane.compareAndSet(false, true)) {
                                shared.incrementAndGet();
                            }
                            Thread.sleep(1);
                            lane.set(false);
                        });
        assertEquals(0, shared.get());
    }

    @Test
    void everyItemRunsExactlyOnce() throws Exception {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        new LanePool<>(lanes(3)).runAll(items(1000).iterator(), (lane, item) -> seen.add(item));
        Collections.sort(seen);
        assertEquals(items(1000), seen);
    }

    @Test
    void lanesActuallyRunAtTheSameTime() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        new LanePool<>(lanes(4))
                .runAll(
                        items(40).iterator(),
                        (lane, item) -> {
                            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                            Thread.sleep(20);
                            active.decrementAndGet();
                        });
        assertEquals(4, peak.get());
    }

    @Test
    void workInFlightIsLimitedByTheLanes() throws Exception {
        AtomicInteger pulled = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        AtomicInteger worst = new AtomicInteger();
        var counting =
                new java.util.Iterator<Integer>() {
                    @Override
                    public boolean hasNext() {
                        return pulled.get() < 200;
                    }

                    @Override
                    public Integer next() {
                        worst.accumulateAndGet(pulled.incrementAndGet() - finished.get(), Math::max);
                        return pulled.get();
                    }
                };
        new LanePool<>(lanes(2))
                .runAll(
                        counting,
                        (lane, item) -> {
                            Thread.sleep(1);
                            finished.incrementAndGet();
                        });
        // Two running, plus the one the caller has pulled while waiting for a lane.
        assertTrue(worst.get() <= 3, "in flight: " + worst.get());
    }

    @Test
    void theFirstFailureStopsNewWorkAndIsRethrown() {
        AtomicInteger started = new AtomicInteger();
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new LanePool<>(lanes(2))
                                        .runAll(
                                                items(100_000).iterator(),
                                                (lane, item) -> {
                                                    started.incrementAndGet();
                                                    throw new IllegalStateException("boom");
                                                }));
        assertEquals("boom", thrown.getMessage());
        assertFalse(started.get() > 1000, "kept starting work after a failure: " + started.get());
    }
}
