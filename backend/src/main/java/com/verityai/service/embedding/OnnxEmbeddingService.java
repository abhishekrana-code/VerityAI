package com.verityai.service.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Local ONNX embedding provider (all-MiniLM-L6-v2, 384 dimensions).
 *
 * <p>Model and tokenizer are downloaded from Hugging Face on first use, verified
 * against pinned SHA-256 checksums, and cached on disk — after the first run the
 * provider works fully offline. If anything fails (no network, checksum drift,
 * missing native libs), the provider marks itself unavailable and retrieval
 * degrades gracefully to keyword full-text search; it never fabricates vectors.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "verityai.embeddings.provider", havingValue = "onnx", matchIfMissing = true)
public class OnnxEmbeddingService implements EmbeddingService {

    static final String MODEL_NAME = "all-MiniLM-L6-v2-quantized";

    @Value("${verityai.embeddings.model-url}")
    private String modelUrl;

    @Value("${verityai.embeddings.model-sha256}")
    private String modelSha256;

    @Value("${verityai.embeddings.tokenizer-url}")
    private String tokenizerUrl;

    @Value("${verityai.embeddings.tokenizer-sha256}")
    private String tokenizerSha256;

    @Value("${verityai.embeddings.cache-dir:${user.home}/.verityai/models}")
    private String cacheDir;

    @Value("${verityai.embeddings.dimensions:384}")
    private int dimensions;

    /** Hard cap on input characters (~500 tokens) so the sequence never exceeds the model's 512 positions. */
    @Value("${verityai.embeddings.max-input-chars:2000}")
    private int maxInputChars;

    @Value("${verityai.embeddings.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${verityai.embeddings.read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Value("${verityai.embeddings.download-timeout-ms:180000}")
    private int downloadTimeoutMs;

    private volatile boolean initialized;
    private volatile boolean unavailable;
    private volatile String unavailableReason = "not initialized";

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment environment;
    private OrtSession session;

    @Override
    public boolean isAvailable() {
        if (unavailable) {
            return false;
        }
        try {
            ensureInitialized();
            return initialized;
        } catch (EmbeddingException e) {
            log.warn("Embeddings unavailable: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("Cannot embed empty text");
        }
        ensureInitialized();

        String input = text.length() > maxInputChars ? text.substring(0, maxInputChars) : text;
        Encoding encoding = tokenizer.encode(input);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] types = encoding.getTypeIds();
        if (ids.length == 0) {
            throw new EmbeddingException("Tokenizer produced no tokens");
        }

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, new long[][]{ids});
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, new long[][]{mask});
             OnnxTensor typesTensor = OnnxTensor.createTensor(environment, new long[][]{types})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typesTensor);
            inputs.keySet().retainAll(session.getInputNames());

            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue output = null;
                for (Map.Entry<String, OnnxValue> entry : result) {
                    output = entry.getValue();
                    break;
                }
                if (output == null) {
                    throw new EmbeddingException("Model produced no outputs");
                }
                float[] vector = toVector(output, mask);
                return normalize(vector);
            }
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("ONNX inference failed: " + e.getMessage(), e);
        }
    }

    /**
     * Accepts either a rank-3 [1, seq, dim] last_hidden_state (mean-pooled with
     * the attention mask) or a rank-2 [1, dim] pooled sentence_embedding.
     */
    private float[] toVector(OnnxValue output, long[] mask) throws OrtException {
        if (!(output instanceof OnnxTensor tensor)) {
            throw new EmbeddingException("Unexpected output type: " + output.getClass().getName());
        }
        Object value = tensor.getValue();
        if (value instanceof float[][][] hidden) {
            float[][] sequence = hidden[0];
            int dim = hidden[0][0].length;
            if (dim != dimensions) {
                throw new EmbeddingException("Model dimension " + dim + " != configured " + dimensions);
            }
            float[] pooled = new float[dim];
            int counted = 0;
            for (int i = 0; i < sequence.length && i < mask.length; i++) {
                if (mask[i] == 0) continue;
                counted++;
                for (int j = 0; j < dim; j++) {
                    pooled[j] += sequence[i][j];
                }
            }
            if (counted == 0) {
                throw new EmbeddingException("Attention mask is all zeros");
            }
            for (int j = 0; j < dim; j++) {
                pooled[j] /= counted;
            }
            return pooled;
        }
        if (value instanceof float[][] pooled2) {
            if (pooled2[0].length != dimensions) {
                throw new EmbeddingException("Model dimension " + pooled2[0].length + " != configured " + dimensions);
            }
            return pooled2[0];
        }
        throw new EmbeddingException("Unsupported output shape: " + value.getClass().getComponentType());
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0 || Double.isNaN(norm)) {
            throw new EmbeddingException("Cannot normalize a zero/NaN vector");
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Lazy, sticky initialization
    // -----------------------------------------------------------------------

    private void ensureInitialized() {
        if (initialized || unavailable) {
            return;
        }
        synchronized (this) {
            if (initialized || unavailable) {
                return;
            }
            try {
                long start = System.currentTimeMillis();
                Path dir = resolveCacheDir();
                Files.createDirectories(dir.resolve(MODEL_NAME));

                Path model = ensureFile(dir.resolve(MODEL_NAME).resolve("model_quantized.onnx"),
                    modelUrl, modelSha256);
                Path tokenizerFile = ensureFile(dir.resolve(MODEL_NAME).resolve("tokenizer.json"),
                    tokenizerUrl, tokenizerSha256);

                tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile);
                environment = OrtEnvironment.getEnvironment();
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(1);
                    session = environment.createSession(model.toString(), options);
                }
                initialized = true;
                log.info("Embeddings ready: {} (dim={}) initialized in {} ms",
                    MODEL_NAME, dimensions, System.currentTimeMillis() - start);
            } catch (Exception e) {
                markUnavailable(e);
                throw new EmbeddingException(unavailableReason, e);
            }
        }
    }

    /** Expands a leading {@code ~} to the user home directory (Java does not). */
    private Path resolveCacheDir() {
        String dir = cacheDir == null || cacheDir.isBlank()
            ? System.getProperty("user.home") + "/.verityai/models" : cacheDir.trim();
        if (dir.startsWith("~")) {
            dir = System.getProperty("user.home") + dir.substring(1);
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    private void markUnavailable(Exception cause) {        unavailable = true;
        unavailableReason = "embeddings unavailable (" + cause.getClass().getSimpleName() + ": "
            + cause.getMessage() + ") — falling back to keyword search";
        // Full stack trace once: this is an operational condition, not a request error.
        log.warn(unavailableReason, cause);
    }

    /** Ensures the file exists locally and matches its pinned checksum, downloading if needed. */
    private Path ensureFile(Path target, String url, String expectedSha) throws IOException {
        if (Files.exists(target)) {
            String actual = sha256(target);
            if (actual.equalsIgnoreCase(expectedSha)) {
                return target;
            }
            log.warn("Cached {} failed checksum verification; re-downloading", target.getFileName());
            Files.delete(target);
        }
        download(url, target);
        String actual = sha256(target);
        if (!actual.equalsIgnoreCase(expectedSha)) {
            Files.deleteIfExists(target);
            throw new IOException("SHA-256 mismatch for " + target.getFileName()
                + " (expected " + expectedSha + ", got " + actual + ")");
        }
        log.info("Downloaded {} ({} bytes, checksum verified)", target.getFileName(), Files.size(target));
        return target;
    }

    private void download(String url, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(downloadTimeoutMs))
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + response.statusCode() + " from " + url);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
