package com.verityai.service.embedding;

/** Raised when embeddings cannot be produced; callers degrade to keyword search. */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
