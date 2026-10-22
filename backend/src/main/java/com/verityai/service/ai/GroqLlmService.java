package com.verityai.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.service.retrieval.RetrievalService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroqLlmService implements LlmService {

    private final ObjectMapper objectMapper;
    private HttpClient httpClient;

    @Value("${verityai.groq.api-key}")
    private String apiKey;

    @Value("${verityai.groq.model:openai/gpt-oss-20b}")
    private String model;

    @Value("${verityai.groq.api-url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    @Value("${verityai.groq.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${verityai.groq.read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Value("${verityai.groq.max-retries:2}")
    private int maxRetries;

    @Value("${verityai.groq.max-tokens:4096}")
    private int maxTokens;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build();
    }

    @Override
    public ChatResponse generate(ChatRequest request) {
        String prompt = buildPrompt(request.question(), request.evidence());

        GroqRequest groqRequest = new GroqRequest(
            request.model(),
            List.of(
                new GroqMessage("system", SYSTEM_PROMPT),
                new GroqMessage("user", prompt)
            ),
            request.maxTokens(),
            0.1,
            1.0
        );

        int attempt = 0;
        while (true) {
            try {
                String json = objectMapper.writeValueAsString(groqRequest);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

                Instant start = Instant.now();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int latencyMs = (int) Duration.between(start, Instant.now()).toMillis();

                if (response.statusCode() == 429) {
                    if (attempt < maxRetries) {
                        attempt++;
                        Thread.sleep(1000L * attempt);
                        continue;
                    }
                    throw new GroqException("Rate limited", 429);
                }

                if (response.statusCode() == 401) {
                    throw new GroqException("Invalid API key", 401);
                }

                if (response.statusCode() >= 500) {
                    if (attempt < maxRetries) {
                        attempt++;
                        Thread.sleep(1000L * attempt);
                        continue;
                    }
                    throw new GroqException("Provider error: " + response.statusCode(), response.statusCode());
                }

                if (response.statusCode() != 200) {
                    throw new GroqException("Unexpected response: " + response.statusCode(), response.statusCode());
                }

                return parseResponse(response.body(), latencyMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GroqException("Request interrupted", 0);
            } catch (JsonProcessingException e) {
                throw new GroqException("Serialization error", 0);
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    attempt++;
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                throw new GroqException("Network error: " + e.getMessage(), 0);
            }
        }
    }

    private String buildPrompt(String question, List<RetrievalService.RetrievedChunk> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("Answer the question using ONLY the provided evidence. ");
        sb.append("Cite sources using [S1], [S2], etc. corresponding to the evidence blocks. ");
        sb.append("If the evidence is insufficient, state that clearly and do not hallucinate.\n\n");

        sb.append("EVIDENCE:\n");
        for (int i = 0; i < evidence.size(); i++) {
            var chunk = evidence.get(i);
            sb.append("Source [S").append(i + 1).append("] (Document: ")
                .append(chunk.chunk().getDocument().getOriginalFilename())
                .append(", Page: ").append(chunk.chunk().getPageNumber()).append("):\n");
            sb.append(chunk.chunk().getContent()).append("\n\n");
        }

        sb.append("QUESTION: ").append(question).append("\n\n");
        sb.append("ANSWER:");

        return sb.toString();
    }

    private ChatResponse parseResponse(String body, int latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choice = root.path("choices").get(0);
            String content = choice.path("message").path("content").asText("");

            List<Integer> citedIndices = extractCitations(content);

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);

            return new ChatResponse(
                content,
                promptTokens,
                completionTokens,
                latencyMs,
                model,
                citedIndices
            );
        } catch (Exception e) {
            log.error("Failed to parse Groq response", e);
            throw new GroqException("Failed to parse response", 0);
        }
    }

    private List<Integer> extractCitations(String content) {
        List<Integer> indices = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[S(\\d+)\\]");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            try {
                int idx = Integer.parseInt(matcher.group(1)) - 1;
                if (idx >= 0 && !indices.contains(idx)) {
                    indices.add(idx);
                }
            } catch (NumberFormatException ignored) {}
        }
        return indices;
    }

    private static final String SYSTEM_PROMPT = """
        You are VerityAI, an evidence-based research assistant.
        Your answers must be grounded strictly in the provided evidence.
        Rules:
        1. Only use information from the EVIDENCE blocks. Do not use external knowledge.
        2. Cite every claim using [S1], [S2], etc. matching the source numbers.
        3. If evidence is insufficient, state: "Insufficient evidence to answer."
        4. Do not speculate, infer beyond the text, or hallucinate.
        5. Be concise and precise.
        """;

    // Internal DTOs
    record GroqRequest(String model, List<GroqMessage> messages, int max_tokens, double temperature, double top_p) {}
    record GroqMessage(String role, String content) {}

    static class GroqException extends RuntimeException {
        private final int statusCode;
        GroqException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}