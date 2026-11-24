package com.verityai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for streamed answers.
 *
 * <p>Kept separate from document processing: a stream holds a thread for as long
 * as the model keeps talking, and it must never delay PDF ingestion or fall back
 * to running on the request thread. The pool is small and bounded; when it is
 * full the request is rejected with a retryable 503, which is honest — the
 * alternative is an unbounded thread pool under load.</p>
 */
@Configuration
public class StreamingConfig {

    @Bean
    public AnswerStreamExecutor answerStreamExecutor() {
        ThreadPoolTaskExecutor delegate = new ThreadPoolTaskExecutor();
        delegate.setCorePoolSize(4);
        delegate.setMaxPoolSize(8);
        delegate.setQueueCapacity(20);
        delegate.setThreadNamePrefix("answer-stream-");
        delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        delegate.setWaitForTasksToCompleteOnShutdown(false);
        delegate.initialize();
        return new AnswerStreamExecutor(delegate);
    }

    /** Unique wrapper type so injection is unambiguous. */
    public static class AnswerStreamExecutor {
        private final ThreadPoolTaskExecutor delegate;

        AnswerStreamExecutor(ThreadPoolTaskExecutor delegate) {
            this.delegate = delegate;
        }

        /**
         * @throws org.springframework.core.task.TaskRejectedException when the
         *         pool and its queue are saturated
         */
        public void submit(Runnable task) {
            delegate.execute(task);
        }
    }
}
