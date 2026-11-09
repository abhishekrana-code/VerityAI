package com.verityai.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the processing pool never falls back to the caller thread. When the
 * worker and queue are saturated the submission is rejected, which the service
 * layer translates into a 503 instead of hanging an upload request.
 */
class ProcessingExecutorTest {

    @Test
    void rejectsInsteadOfRunningOnTheCallerThreadWhenSaturated() throws Exception {
        ThreadPoolTaskExecutor delegate = new ThreadPoolTaskExecutor();
        delegate.setCorePoolSize(1);
        delegate.setMaxPoolSize(1);
        delegate.setQueueCapacity(1);
        delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        delegate.initialize();
        ProcessingConfig.ProcessingExecutor executor = new ProcessingConfig.ProcessingExecutor(delegate);

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                running.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            executor.submit(() -> { }); // occupies the single queue slot
            assertThat(executor.hasCapacity()).isFalse();

            assertThatThrownBy(() -> executor.submit(() -> { }))
                .isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            delegate.shutdown();
        }
    }
}
