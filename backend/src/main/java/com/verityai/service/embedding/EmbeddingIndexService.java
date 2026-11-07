package com.verityai.service.embedding;

import com.verityai.entity.DocumentChunk;
import com.verityai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Writes chunk embeddings for documents going through the processing pipeline
 * and for the startup backfill.
 *
 * <p>Everything here is best-effort by design: a failure leaves the chunk's
 * embedding NULL (it remains findable via full-text search, and the backfill
 * retries later). Embedding problems can never fail document processing.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingIndexService {

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository chunkRepository;

    @Value("${verityai.embeddings.index-batch-size:64}")
    private int batchSize;

    @Value("${verityai.embeddings.max-consecutive-failures:5}")
    private int maxConsecutiveFailures;

    /** Embeds all unembedded chunks of a document. Never throws. */
    public void embedDocument(UUID documentId) {
        if (!embeddingService.isAvailable()) {
            return;
        }
        try {
            List<DocumentChunk> pending = chunkRepository.findUnembeddedByDocumentId(documentId);
            if (pending.isEmpty()) {
                return;
            }
            int embedded = 0;
            int failed = 0;
            int consecutiveFailures = 0;
            for (DocumentChunk chunk : pending) {
                try {
                    String vector = toVectorLiteral(embeddingService.embed(chunk.getContent()));
                    chunkRepository.setEmbeddingIfAbsent(chunk.getId(), vector);
                    embedded++;
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    failed++;
                    consecutiveFailures++;
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        log.warn("Aborting embedding for document {} after {} consecutive failures: {}",
                            documentId, consecutiveFailures, e.getMessage());
                        break;
                    }
                }
            }
            log.info("Indexed embeddings for document {}: {} embedded, {} failed of {} chunks",
                documentId, embedded, failed, pending.size());
        } catch (Exception e) {
            // Never propagate: processing must complete; backfill will retry.
            log.warn("Embedding indexing failed for document {}: {}", documentId, e.getMessage());
        }
    }

    /**
     * Embeds up to {@code limit} of the oldest unembedded chunks across all
     * documents (idempotent — safe to run on every startup).
     *
     * @return number of chunks embedded in this pass
     */
    public int backfill(int limit) {
        if (!embeddingService.isAvailable() || limit <= 0) {
            return 0;
        }
        int processed = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        while (processed < limit) {
            int take = Math.min(batchSize, limit - processed);
            List<DocumentChunk> batch = chunkRepository.findUnembedded(take);
            if (batch.isEmpty()) {
                break;
            }
            for (DocumentChunk chunk : batch) {
                try {
                    String vector = toVectorLiteral(embeddingService.embed(chunk.getContent()));
                    chunkRepository.setEmbeddingIfAbsent(chunk.getId(), vector);
                    processed++;
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    failed++;
                    processed++;
                    consecutiveFailures++;
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        log.warn("Embedding backfill aborted after {} consecutive failures: {}",
                            consecutiveFailures, e.getMessage());
                        return processed;
                    }
                }
            }
        }
        log.info("Embedding backfill pass: {} processed ({} failed)", processed, failed);
        return processed;
    }

    /** Clears and re-queues this user's chunks for embedding (safe re-indexing). */
    public int clearUserEmbeddings(UUID userId) {
        int cleared = chunkRepository.clearEmbeddingsForUser(userId);
        log.info("Cleared {} embeddings for re-indexing (user {})", cleared, userId);
        return cleared;
    }

    /** Float array → pgvector literal, e.g. {@code [0.1,-0.2,...]}. */
    static String toVectorLiteral(float[] vector) {
        return EmbeddingService.toVectorLiteral(vector);
    }
}
