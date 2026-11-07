package com.verityai.service.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Placeholder provider used when {@code verityai.embeddings.provider=off}.
 * Always unavailable so retrieval cleanly falls back to full-text search.
 */
@Service
@ConditionalOnProperty(name = "verityai.embeddings.provider", havingValue = "off")
public class NoopEmbeddingService implements EmbeddingService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public int dimensions() {
        return 0;
    }

    @Override
    public String modelName() {
        return "disabled";
    }

    @Override
    public float[] embed(String text) {
        throw new EmbeddingException("Embeddings are disabled (verityai.embeddings.provider=off)");
    }
}
