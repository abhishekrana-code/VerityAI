package com.verityai.config;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-worker queue with a fixed capacity for regenerable background work
 * (document summaries, knowledge graphs).
 *
 * <p>Uploads can arrive faster than a worker can derive summaries or graphs.
 * An unbounded queue would grow with every upload and delay or exhaust the
 * process, so submissions are rejected once the worker is busy and the queue is
 * full. Rejection is explicit: {@link #submit} returns {@code false}, the drop
 * is logged with a running total, and the caller decides how to defer (both
 * backfills leave the document's derived status pending for the next startup or
 * an explicit retry).</p>
 *
 * <p>The rejection policy is {@link ThreadPoolExecutor.AbortPolicy} on purpose:
 * a {@code CallerRunsPolicy} would run summary/graph work on the document
 * processing thread, which is the blocking this queue exists to avoid.</p>
 */
@Slf4j
public final class BoundedTaskQueue {

    private final String name;
    private final int capacity;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    public BoundedTaskQueue(String name, String threadName, int capacity) {
        this.name = name;
        this.capacity = Math.max(1, capacity);
        this.executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(this.capacity),
            runnable -> {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * @return {@code true} when the work was accepted; {@code false} when the
     *         worker and the queue were both full and the work was dropped
     */
    public boolean submit(Runnable task, UUID documentId) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            long total = dropped.incrementAndGet();
            log.warn("{} queue is full (capacity {}): dropped work for document {}. "
                    + "It stays pending for the startup backfill or an explicit retry. "
                    + "{} job(s) dropped since start.",
                name, capacity, documentId, total);
            return false;
        }
    }

    /** Submissions rejected because the queue was full since startup. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Tasks waiting in the queue (excluding the one being processed). */
    public int queuedCount() {
        return executor.getQueue().size();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
