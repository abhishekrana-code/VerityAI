package com.verityai.config;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedTaskQueueTest {

    @Test
    void submit_rejectsWorkWhenWorkerAndQueueAreFull() throws Exception {
        BoundedTaskQueue queue = new BoundedTaskQueue("Test", "bounded-test-worker", 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            // Occupy the single worker so nothing drains the queue.
            assertThat(queue.submit(() -> {
                started.countDown();
                await(release);
            }, UUID.randomUUID())).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(queue.submit(() -> { }, UUID.randomUUID())).isTrue();
            assertThat(queue.queuedCount()).isEqualTo(1);

            // Worker busy + queue full -> rejected instead of queued without limit.
            assertThat(queue.submit(() -> { }, UUID.randomUUID())).isFalse();
            assertThat(queue.droppedCount()).isEqualTo(1);

            assertThat(queue.submit(() -> { }, UUID.randomUUID())).isFalse();
            assertThat(queue.droppedCount()).isEqualTo(2);
        } finally {
            release.countDown();
            queue.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
