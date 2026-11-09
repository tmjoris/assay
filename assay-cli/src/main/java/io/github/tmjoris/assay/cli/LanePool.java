package io.github.tmjoris.assay.cli;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs work items concurrently, each with exclusive use of one lane.
 *
 * <p>A lane is a set of engine JVMs, and a JVM can only answer one query at a time, so a lane must
 * never be shared. The free lanes sit in a blocking queue and the caller takes one before starting
 * each task, which limits the work in flight to the number of lanes. Items are pulled only on the
 * calling thread, so a generator that is not thread safe stays on one thread and produces the same
 * sequence at any parallelism.
 *
 * <p>Tasks run on virtual threads, since they spend nearly all their time blocked on a worker's
 * reply.
 */
final class LanePool<L> {

    @FunctionalInterface
    interface Task<L, T> {
        void run(L lane, T item) throws Exception;
    }

    private final BlockingQueue<L> idle;

    LanePool(Collection<L> lanes) {
        idle = new ArrayBlockingQueue<>(lanes.size(), false, lanes);
    }

    /** Returns once every started task has finished. Rethrows the first failure, and stops starting new items after it. */
    <T> void runAll(Iterator<T> items, Task<L, T> task) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (failure.get() == null && items.hasNext()) {
                T item = items.next();
                L lane = idle.take();
                executor.submit(
                        () -> {
                            try {
                                task.run(lane, item);
                            } catch (Exception e) {
                                failure.compareAndSet(null, e);
                            } finally {
                                idle.add(lane);
                            }
                        });
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
