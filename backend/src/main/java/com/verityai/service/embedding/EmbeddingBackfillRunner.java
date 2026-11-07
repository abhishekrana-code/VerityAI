package com.verityai.service.embedding;

import com.verityai.config.ProcessingConfig.ProcessingExecutor;
import com.verityai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Startup backfill for chunk embeddings (documents uploaded before semantic
 * search existed, or chunks whose embedding failed transiently).
 *
 * <p>Runs asynchronously on the bounded processing executor so startup is not
 * blocked, is capped per start ({@code verityai.embeddings.backfill-limit}),
 * and is skipped entirely (with an honest log line) when embeddings are
 * unavailable — the system keeps working keyword-only.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "verityai.embeddings.provider", havingValue = "onnx", matchIfMissing = true)
public class EmbeddingBackfillRunner implements ApplicationRunner {

    private final EmbeddingService embeddingService;
    private final EmbeddingIndexService embeddingIndexService;
    private final DocumentChunkRepository chunkRepository;
    private final ProcessingExecutor processingExecutor;

    @Value("${verityai.embeddings.backfill-limit:5000}")
    private int backfillLimit;

    @Override
    public void run(ApplicationArguments args) {
        if (!embeddingService.isAvailable()) {
            log.info("Embedding backfill skipped: embeddings unavailable (keyword-only retrieval)");
            return;
        }
        long pending;
        try {
            pending = chunkRepository.countUnembedded();
        } catch (Exception e) {
            log.warn("Could not count unembedded chunks ({}); backfill skipped", e.getMessage());
            return;
        }
        if (pending == 0) {
            log.info("Embedding backfill: all chunks already embedded");
            return;
        }
        int limit = (int) Math.min(pending, backfillLimit);
        log.info("Embedding backfill queued: {} chunks pending, processing up to {} asynchronously", pending, limit);
        processingExecutor.submit(() -> {
            try {
                int done = embeddingIndexService.backfill(limit);
                long remaining = chunkRepository.countUnembedded();
                log.info("Embedding backfill finished: {} embedded this pass, {} still pending", done, remaining);
            } catch (Exception e) {
                log.warn("Embedding backfill failed: {}", e.getMessage());
            }
        });
    }
}
