package com.verityai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated bounded executor for document processing (extract → chunk → embed)
 * and embedding backfill.
 *
 * <p>The pool is deliberately bounded and uses {@link ThreadPoolExecutor.AbortPolicy}:
 * when the queue is full the submission is rejected instead of running PDF
 * extraction on the Tomcat request thread. Callers translate rejection into a
 * {@code 503} with {@code Retry-After}; {@code DocumentService} also checks
 * {@link ProcessingExecutor#hasCapacity()} before accepting an upload so the
 * common case is rejected before the file is stored.</p>
 */
@Configuration
public class ProcessingConfig {

    @Bean
    public ProcessingExecutor documentProcessingExecutor() {
        ThreadPoolTaskExecutor delegate = new ThreadPoolTaskExecutor();
        delegate.setCorePoolSize(2);
        delegate.setMaxPoolSize(4);
        delegate.setQueueCapacity(100);
        delegate.setThreadNamePrefix("docproc-");
        delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        delegate.setWaitForTasksToCompleteOnShutdown(false);
        delegate.initialize();
        return new ProcessingExecutor(delegate);
    }

    /** Unique wrapper type so injection is unambiguous next to Spring's default task executor. */
    public static class ProcessingExecutor {
        private final ThreadPoolTaskExecutor delegate;

        ProcessingExecutor(ThreadPoolTaskExecutor delegate) {
            this.delegate = delegate;
        }

        /**
         * Submits work without ever running it on the calling thread.
         *
         * @throws org.springframework.core.task.TaskRejectedException when the
         *         pool and queue are saturated
         */
        public void submit(Runnable task) {
            delegate.execute(task);
        }

        /**
         * True when a new task can be accepted: either a worker is free or the
         * queue still has room. Conservative under races, which is fine — the
         * pre-check is an early-out, and {@link #submit} is still authoritative.
         */
        public boolean hasCapacity() {
            ThreadPoolExecutor executor = delegate.getThreadPoolExecutor();
            return executor.getActiveCount() < executor.getMaximumPoolSize()
                || executor.getQueue().remainingCapacity() > 0;
        }

        public ThreadPoolTaskExecutor delegate() {
            return delegate;
        }
    }
}
