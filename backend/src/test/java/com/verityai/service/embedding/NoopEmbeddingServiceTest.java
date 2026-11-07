package com.verityai.service.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoopEmbeddingServiceTest {

    private final NoopEmbeddingService service = new NoopEmbeddingService();

    @Test
    void neverAvailable() {
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.dimensions()).isZero();
        assertThat(service.modelName()).isEqualTo("disabled");
    }

    @Test
    void throwsInsteadOfFabricatingVectors() {
        assertThatThrownBy(() -> service.embed("anything"))
            .isInstanceOf(EmbeddingException.class);
    }
}
