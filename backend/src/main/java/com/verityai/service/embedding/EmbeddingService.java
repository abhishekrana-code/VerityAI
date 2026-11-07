package com.verityai.service.embedding;

/**
 * Produces fixed-size vector embeddings for text.
 *
 * <p>Implemented locally (ONNX) so document data never leaves the machine and
 * no external embedding API is required. The interface keeps the provider
 * swappable without touching retrieval code — see {@link OnnxEmbeddingService}.</p>
 */
public interface EmbeddingService {

    /**
     * Whether this provider can currently produce embeddings (model loaded or
     * loadable). When false, retrieval must gracefully degrade to keyword
     * full-text search — never silently produce fake vectors.
     */
    boolean isAvailable();

    /** Embedding dimensionality (e.g. 384 for MiniLM-L6). */
    int dimensions();

    /** Human-readable model identifier, surfaced in API responses for transparency. */
    String modelName();

    /**
     * Embed a single text.
     *
     * @throws EmbeddingException if the provider is unavailable or embedding fails
     */
    float[] embed(String text);

    /** Float array → pgvector literal, e.g. {@code [0.1,-0.2]}. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }
}
