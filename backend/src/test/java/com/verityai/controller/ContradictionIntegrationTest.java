package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.User;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.ai.LlmService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 8 contradiction detection over real PostgreSQL, with a mocked
 * {@link LlmService} so no external API is called.
 *
 * <p>The tests cover the contract that matters: only flagged, validly-classified
 * pairs are stored with both claims and citations; failed detection stores
 * nothing and keeps earlier findings; scopes and ownership are enforced.</p>
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads-contradictions",
    "verityai.summary.enabled=false",
    "verityai.embeddings.provider=off",
    "verityai.knowledge.backfill-limit=0",
    "verityai.contradictions.max-pairs=10",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class ContradictionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @MockitoBean private LlmService llmService;

    private record Csrf(Cookie cookie, String token) {
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String uniqueEmail() {
        return "contradiction-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Contradiction Test"))))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie after register").isNotNull();
        return session;
    }

    private Csrf csrf(Cookie session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("XSRF-TOKEN cookie after /auth/csrf").isNotNull();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("token").asText();
        return new Csrf(xsrf, token);
    }

    private User user(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private Document document(User owner, String filename, Document.DocumentStatus status) {
        Document document = new Document();
        document.setUser(owner);
        document.setOriginalFilename(filename);
        document.setStoredFilename("stored-" + UUID.randomUUID() + "-" + filename);
        document.setMimeType("application/pdf");
        document.setSizeBytes(2048L);
        document.setSha256((UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "")).substring(0, 64));
        document.setStatus(status);
        return documentRepository.save(document);
    }

    private DocumentChunk chunk(Document document, int index, int page, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setPageNumber(page);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTokenCount(Math.max(1, content.length() / 4));
        return chunkRepository.save(chunk);
    }

    private MvcResult detect(Cookie session, Csrf csrf, String documentIdsJson) throws Exception {
        return mockMvc.perform(post("/api/v1/contradictions/detect")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(documentIdsJson))
            .andExpect(status().isOk())
            .andReturn();
    }

    private static LlmService.CompletionResponse completion(String content) {
        return new LlmService.CompletionResponse(content, 12, 10, 7, "test-contradiction-model");
    }

    // ------------------------------------------------------------------
    // Detection + read models
    // ------------------------------------------------------------------

    @Test
    void detectionStoresOnlyClassifiedPairsWithBothClaimsAndCitations() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document report = document(user(email), "annual-report.pdf", Document.DocumentStatus.READY);
        Document notes = document(user(email), "board-notes.pdf", Document.DocumentStatus.READY);
        chunk(report, 0, 1, "Alpha Corp reported revenue growth of 42% in 2023.");
        chunk(notes, 0, 2, "Alpha Corp reported revenue growth of 38% in 2023.");

        when(llmService.complete(any(LlmService.CompletionRequest.class))).thenReturn(completion("""
            [{"i":0,"contradiction":true,"type":"NUMERIC_CONFLICT","severity":"HIGH",
              "why":"The revenue growth values differ for the same company and year."}]
            """));

        MvcResult detected = detect(session, csrf,
            "{\"documentIds\":[\"" + report.getId() + "\"]}");
        JsonNode result = objectMapper.readTree(detected.getResponse().getContentAsString());

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("scope").asText()).isEqualTo("DOCUMENT");
        assertThat(result.get("analyzedDocuments").asInt()).isEqualTo(2);
        assertThat(result.get("candidatePairs").asInt()).isEqualTo(1);
        assertThat(result.get("found").asInt()).isEqualTo(1);
        assertThat(result.get("stored").asInt()).isEqualTo(1);
        assertThat(result.get("model").asText()).isEqualTo("test-contradiction-model");
        assertThat(result.get("detectedAt").asText()).isNotBlank();

        MvcResult listed = mockMvc.perform(get("/api/v1/contradictions")
                .cookie(session)
                .param("documentId", report.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andReturn();
        JsonNode item = objectMapper.readTree(listed.getResponse().getContentAsString()).at("/content/0");

        assertThat(item.get("contradictionType").asText()).isEqualTo("NUMERIC_CONFLICT");
        assertThat(item.get("severity").asText()).isEqualTo("HIGH");
        assertThat(item.get("claimA").asText()).contains("42%");
        assertThat(item.get("claimB").asText()).contains("38%");
        assertThat(item.get("documentAName").asText()).isEqualTo("annual-report.pdf");
        assertThat(item.get("documentBName").asText()).isEqualTo("board-notes.pdf");
        assertThat(item.get("pageA").asInt()).isEqualTo(1);
        assertThat(item.get("pageB").asInt()).isEqualTo(2);
        assertThat(item.get("documentAId").asText()).isEqualTo(report.getId().toString());
        assertThat(item.get("documentBId").asText()).isEqualTo(notes.getId().toString());
        assertThat(item.get("explanation").asText()).contains("revenue growth values differ");
        assertThat(item.get("detectedAt").asText()).isNotBlank();

        mockMvc.perform(get("/api/v1/contradictions/stats").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.byType.NUMERIC_CONFLICT").value(1))
            .andExpect(jsonPath("$.byType.TEMPORAL_CONFLICT").value(0))
            .andExpect(jsonPath("$.byType.ATTRIBUTE_CONFLICT").value(0))
            .andExpect(jsonPath("$.byType.CAUSAL_CONFLICT").value(0))
            .andExpect(jsonPath("$.byType.POLARITY_CONFLICT").value(0))
            .andExpect(jsonPath("$.bySeverity.HIGH").value(1))
            .andExpect(jsonPath("$.bySeverity.MEDIUM").value(0))
            .andExpect(jsonPath("$.bySeverity.LOW").value(0))
            .andExpect(jsonPath("$.lastDetectedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/contradictions")
                .cookie(session)
                .param("type", "TEMPORAL_CONFLICT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/contradictions")
                .cookie(session)
                .param("type", "SCOPE_CONFLICT"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void failedDetectionStoresNothingAndKeepsEarlierFindings() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document report = document(user(email), "annual-report.pdf", Document.DocumentStatus.READY);
        Document notes = document(user(email), "board-notes.pdf", Document.DocumentStatus.READY);
        chunk(report, 0, 1, "Alpha Corp reported revenue growth of 42% in 2023.");
        chunk(notes, 0, 2, "Alpha Corp reported revenue growth of 38% in 2023.");

        String body = "{\"documentIds\":[\"" + report.getId() + "\"]}";

        when(llmService.complete(any(LlmService.CompletionRequest.class))).thenReturn(completion("""
            [{"i":0,"contradiction":true,"type":"NUMERIC_CONFLICT","severity":"HIGH","why":"values differ"}]
            """));
        detect(session, csrf, body);

        // Model failure: no write, earlier finding untouched.
        when(llmService.complete(any(LlmService.CompletionRequest.class)))
            .thenThrow(new RuntimeException("upstream timeout"));
        JsonNode failed = objectMapper.readTree(detect(session, csrf, body).getResponse().getContentAsString());
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("stored").asInt()).isZero();
        assertThat(failed.get("error").asText()).contains("Model call failed");

        mockMvc.perform(get("/api/v1/contradictions").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));

        // Invalid model output: also a failure, not an empty result.
        when(llmService.complete(any(LlmService.CompletionRequest.class)))
            .thenReturn(completion("the model rambled instead of JSON"));
        JsonNode invalid = objectMapper.readTree(detect(session, csrf, body).getResponse().getContentAsString());
        assertThat(invalid.get("status").asText()).isEqualTo("FAILED");
        assertThat(invalid.get("stored").asInt()).isZero();

        mockMvc.perform(get("/api/v1/contradictions").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));

        // A valid empty result is a success and replaces the scope.
        when(llmService.complete(any(LlmService.CompletionRequest.class))).thenReturn(completion("[]"));
        JsonNode empty = objectMapper.readTree(detect(session, csrf, body).getResponse().getContentAsString());
        assertThat(empty.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(empty.get("found").asInt()).isZero();
        assertThat(empty.get("stored").asInt()).isZero();

        mockMvc.perform(get("/api/v1/contradictions").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void noComparableClaimsCompletesWithoutCallingTheModel() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document first = document(user(email), "weather.pdf", Document.DocumentStatus.READY);
        Document second = document(user(email), "minutes.pdf", Document.DocumentStatus.READY);
        chunk(first, 0, 1, "Weather was mild across the region.");
        chunk(second, 0, 2, "The meeting started promptly at noon.");

        JsonNode result = objectMapper.readTree(
            detect(session, csrf, "{}").getResponse().getContentAsString());

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("candidatePairs").asInt()).isZero();
        assertThat(result.get("found").asInt()).isZero();
        assertThat(result.get("stored").asInt()).isZero();
        assertThat(result.get("model").isNull()).isTrue();
        verify(llmService, never()).complete(any(LlmService.CompletionRequest.class));

        mockMvc.perform(get("/api/v1/contradictions").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // Ownership / validation / authentication
    // ------------------------------------------------------------------

    @Test
    void detectionIsOwnershipScopedAndRejectsUnprocessedDocuments() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Csrf ownerCsrf = csrf(ownerSession);
        Document owned = document(user(ownerEmail), "private-report.pdf", Document.DocumentStatus.READY);
        chunk(owned, 0, 1, "Alpha Corp reported revenue growth of 42% in 2023.");

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);
        Csrf intruderCsrf = csrf(intruderSession);

        mockMvc.perform(get("/api/v1/contradictions").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/contradictions/stats").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/contradictions")
                .cookie(intruderSession)
                .param("documentId", owned.getId().toString()))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/contradictions/detect")
                .cookie(intruderSession, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + owned.getId() + "\"]}"))
            .andExpect(status().isNotFound());

        Document processing = document(user(ownerEmail), "still-processing.pdf",
            Document.DocumentStatus.PROCESSING);
        mockMvc.perform(post("/api/v1/contradictions/detect")
                .cookie(ownerSession, ownerCsrf.cookie())
                .header("X-XSRF-TOKEN", ownerCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + processing.getId() + "\"]}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/contradictions/detect")
                .cookie(ownerSession, ownerCsrf.cookie())
                .header("X-XSRF-TOKEN", ownerCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + UUID.randomUUID() + "\"]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void readsRequireAuthenticationAndDetectionRequiresCsrf() throws Exception {
        mockMvc.perform(get("/api/v1/contradictions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/contradictions/stats")).andExpect(status().isUnauthorized());

        String email = uniqueEmail();
        Cookie session = register(email);

        mockMvc.perform(post("/api/v1/contradictions/detect")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }
}
