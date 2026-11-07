package com.verityai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated bounded executor for document processing (extract → chunk → embed)
 * and embedding backfill.
 *
 * <p>Previously these tasks ran on the JVM's common ForkJoin pool, where a burst
 * of uploads could starve unrelated async work. The bounded pool + CallerRuns
 * policy keeps the system responsive: if the queue ever fills, the submitting
 * thread does the work instead of dropping the job.</p>
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
        delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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

        public void submit(Runnable task) {
            delegate.execute(task);
        }

        public ThreadPoolTaskExecutor delegate() {
            return delegate;
        }
    }
}
