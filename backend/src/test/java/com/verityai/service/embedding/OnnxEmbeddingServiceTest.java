package com.verityai.service.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real ONNX model from the local cache (downloads + checksum-verifies
 * on first use). If the model cannot be obtained (offline), the substantive
 * assertions are skipped rather than silently passing — reported by JUnit as skipped.
 */
class OnnxEmbeddingServiceTest {

    private static final int DIM = 384;
    private static final String REV = "751bff37182d3f1213fa05d7196b954e230abad9";

    private OnnxEmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new OnnxEmbeddingService();
        ReflectionTestUtils.setField(service, "modelUrl",
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/" + REV + "/onnx/model_quantized.onnx");
        ReflectionTestUtils.setField(service, "modelSha256",
            "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1");
        ReflectionTestUtils.setField(service, "tokenizerUrl",
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/" + REV + "/tokenizer.json");
        ReflectionTestUtils.setField(service, "tokenizerSha256",
            "da0e79933b9ed51798a3ae27893d3c5fa4a201126cef75586296df9b4d2c62a0");
        ReflectionTestUtils.setField(service, "cacheDir", System.getProperty("user.home") + "/.verityai/models");
        ReflectionTestUtils.setField(service, "dimensions", DIM);
        ReflectionTestUtils.setField(service, "maxInputChars", 2000);
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 10000);
        ReflectionTestUtils.setField(service, "readTimeoutMs", 60000);
        ReflectionTestUtils.setField(service, "downloadTimeoutMs", 180000);
    }

    @Test
    void producesNormalized384DimensionVectors() {
        assumeTrue(service.isAvailable(), "ONNX model unavailable (no cache and no network) — skipped");
        float[] vector = service.embed("Volunteers planted thousands of trees across fourteen regions.");

        assertThat(vector).hasSize(DIM);
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        for (float v : vector) {
            assertThat(Float.isNaN(v)).isFalse();
        }
    }

    @Test
    void deterministicForIdenticalInput() {
        assumeTrue(service.isAvailable(), "ONNX model unavailable — skipped");
        String text = "The board approved a new budget for the coming fiscal year.";
        float[] a = service.embed(text);
        float[] b = service.embed(text);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void semanticSimilarityBeatsUnrelatedText() {
        assumeTrue(service.isAvailable(), "ONNX model unavailable — skipped");
        float[] query = service.embed("how to grow young trees in a nursery");
        float[] related = service.embed("planting saplings and caring for seedlings");
        float[] unrelated = service.embed("quarterly revenue and profit margin reporting");

        double relatedScore = cosine(query, related);
        double unrelatedScore = cosine(query, unrelated);

        assertThat(relatedScore)
            .as("paraphrase (%.3f) must outrank unrelated text (%.3f)", relatedScore, unrelatedScore)
            .isGreaterThan(unrelatedScore + 0.05);
    }

    @Test
    void emptyTextThrowsWithoutTouchingTheModel() {
        assertThatThrownBy(() -> service.embed("   "))
            .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void longInputIsTruncatedNotRejected() {
        assumeTrue(service.isAvailable(), "ONNX model unavailable — skipped");
        String huge = "revenue grew steadily ".repeat(500);
        float[] vector = service.embed(huge);
        assertThat(vector).hasSize(DIM);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot; // both vectors are L2-normalized
    }
}
