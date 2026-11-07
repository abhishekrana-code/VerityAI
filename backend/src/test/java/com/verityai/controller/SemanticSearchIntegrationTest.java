package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.User;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.embedding.EmbeddingIndexService;
import com.verityai.service.embedding.EmbeddingService;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Feature 4: hybrid semantic + keyword search over a real pgvector
 * PostgreSQL container. Documents and chunks are seeded directly (no upload
 * pipeline), embeddings are computed with the real local ONNX model, and the
 * assertions cover the honest-reporting contract:
 *
 * <ul>
 *   <li>a paraphrase with zero lexical overlap is found via {@code strategy=semantic};</li>
 *   <li>keyword+semantic overlap reports {@code strategy=hybrid} with both match flags;</li>
 *   <li>unindexed chunks fall back to keyword ({@code semanticMatch=false}, {@code similarity=null});</li>
 *   <li>foreign document scopes are rejected (403), other users' chunks never leak;</li>
 *   <li>the server-side result cap is honored (fused union is trimmed).</li>
 * </ul>
 *
 * <p>Tests that need the semantic branch are skipped (not failed) when the
 * embedding model is unavailable, so the suite stays honest on machines
 * without the cached model.</p>
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads-search",
    "verityai.summary.enabled=false",
    "verityai.search.max-results=5",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class SemanticSearchIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private EmbeddingIndexService embeddingIndexService;
    @Autowired private EmbeddingService embeddingService;

    private record Csrf(Cookie cookie, String token) {}

    private String uniqueEmail() {
        return "sem-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Search Test"))))
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
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        return new Csrf(xsrf, token);
    }

    private User user(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private Document document(User owner, String filename) {
        Document doc = new Document();
        doc.setUser(owner);
        doc.setOriginalFilename(filename);
        doc.setStoredFilename("stored-" + filename);
        doc.setMimeType("application/pdf");
        doc.setSizeBytes(1024L);
        // 64 hex chars, unique per call (sha256 is unique-constrained)
        doc.setSha256((UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "")).substring(0, 64));
        doc.setStatus(Document.DocumentStatus.READY);
        return documentRepository.save(doc);
    }

    private DocumentChunk chunk(Document doc, int index, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setDocument(doc);
        c.setPageNumber(1);
        c.setChunkIndex(index);
        c.setContent(content);
        return chunkRepository.save(c);
    }

    private String search(Cookie session, Csrf csrf, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andReturn();
        return result.getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------
    // Semantic branch: paraphrase with zero keyword overlap
    // ------------------------------------------------------------------

    @Test
    void paraphraseWithZeroKeywordOverlapIsFoundSemantically() throws Exception {
        assumeTrue(embeddingService.isAvailable(), "embedding model unavailable — semantic branch not testable here");

        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "retention-report.pdf");
        // Lexically disjoint from the query below (verified by the keywordMatch=false assertion).
        chunk(doc, 0, "Churn among paying users climbed sharply after the subscription price increase last quarter.");
        embeddingIndexService.embedDocument(doc.getId());

        String body = """
            {"query":"Why did so many people stop staying once the plan cost went up?","documentIds":["%s"]}
            """.formatted(doc.getId());

        String response = search(session, csrf, body);
        JsonNode root = objectMapper.readTree(response);

        assertThat(root.get("strategy").asText()).isEqualTo("semantic");
        assertThat(root.get("count").asInt()).isEqualTo(1);
        assertThat(root.get("model").asText()).as("embedding model reported for semantic branch").isNotBlank();

        JsonNode hit = root.at("/results/0");
        assertThat(hit.get("documentId").asText()).isEqualTo(doc.getId().toString());
        assertThat(hit.get("semanticMatch").asBoolean()).isTrue();
        assertThat(hit.get("keywordMatch").asBoolean())
            .as("zero lexical overlap must not produce a keyword match")
            .isFalse();
        assertThat(hit.get("similarity").asDouble()).isGreaterThanOrEqualTo(0.10);
        assertThat(hit.get("rank").asDouble()).isBetween(0.0, 1.0);
        assertThat(hit.get("content").asText()).isNotBlank();
        assertThat(hit.get("pageNumber").asInt()).isEqualTo(1);
    }

    @Test
    void overlappingKeywordAndSemanticMatchesReportHybridStrategy() throws Exception {
        assumeTrue(embeddingService.isAvailable(), "embedding model unavailable — semantic branch not testable here");

        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "pricing-report.pdf");
        chunk(doc, 0, "Quarterly customer churn rose to 7 percent after the pricing change.");
        embeddingIndexService.embedDocument(doc.getId());

        String body = """
            {"query":"customer churn","documentIds":["%s"]}
            """.formatted(doc.getId());

        JsonNode root = objectMapper.readTree(search(session, csrf, body));

        assertThat(root.get("strategy").asText()).isEqualTo("hybrid");
        assertThat(root.get("count").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode hit = root.at("/results/0");
        assertThat(hit.get("keywordMatch").asBoolean()).isTrue();
        assertThat(hit.get("semanticMatch").asBoolean()).isTrue();
        assertThat(hit.get("similarity").asDouble()).isGreaterThanOrEqualTo(0.10);
        assertThat(hit.get("rank").asDouble()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // Not-yet-indexed chunks: keyword still works, semantic honestly absent
    // ------------------------------------------------------------------

    @Test
    void unindexedChunkIsFoundByKeywordOnly() throws Exception {
        assumeTrue(embeddingService.isAvailable(), "embedding model unavailable — semantic branch not testable here");

        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "glaciology-notes.pdf");
        chunk(doc, 0, "Glaciology teams drilled ice core samples from the Antarctic plateau.");
        // deliberately NOT embedded (never indexDocument)

        String body = """
            {"query":"ice core samples","documentIds":["%s"]}
            """.formatted(doc.getId());

        JsonNode root = objectMapper.readTree(search(session, csrf, body));

        assertThat(root.get("strategy").asText()).isEqualTo("keyword");
        assertThat(root.get("count").asInt()).isEqualTo(1);

        JsonNode hit = root.at("/results/0");
        assertThat(hit.get("keywordMatch").asBoolean()).isTrue();
        assertThat(hit.get("semanticMatch").asBoolean()).isFalse();
        assertThat(hit.get("similarity").isNull())
            .as("keyword-only hits must not report a cosine similarity")
            .isTrue();
    }

    @Test
    void paraphraseOfUnindexedDocumentReturnsNoHits() throws Exception {
        assumeTrue(embeddingService.isAvailable(), "embedding model unavailable — semantic branch not testable here");

        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "sediment-study.pdf");
        chunk(doc, 0, "Geologists extracted sediment layers near the south pole.");
        // deliberately NOT embedded — a paraphrase can only be found semantically,
        // and there is no embedding, so the honest answer is "no results".

        String body = """
            {"query":"scientists pulled frozen material from beneath the polar ice sheet","documentIds":["%s"]}
            """.formatted(doc.getId());

        JsonNode root = objectMapper.readTree(search(session, csrf, body));

        assertThat(root.get("count").asInt())
            .as("unindexed content must not be returned for a semantic-only query")
            .isEqualTo(0);
        assertThat(root.get("strategy").asText()).isEqualTo("keyword");
        assertThat(root.get("results").isArray()).isTrue();
    }

    // ------------------------------------------------------------------
    // Authorization and isolation
    // ------------------------------------------------------------------

    @Test
    void foreignDocumentScopeIsRejectedWith403() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Document owned = document(user(ownerEmail), "private.pdf");
        chunk(owned, 0, "Confidential merger details for the owner only.");

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);
        Csrf intruderCsrf = csrf(intruderSession);

        mockMvc.perform(post("/api/v1/search")
                .cookie(intruderSession, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"merger\",\"documentIds\":[\"" + owned.getId() + "\"]}"))
            .andExpect(status().isForbidden());

        assertThat(ownerSession).isNotNull(); // owner data untouched
    }

    @Test
    void otherUsersChunksNeverAppearInUnscopedSearch() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Document owned = document(user(ownerEmail), "owner-doc.pdf");
        chunk(owned, 0, "proprietary widget forecast numbers for next year");

        String searcherEmail = uniqueEmail();
        Cookie searcherSession = register(searcherEmail);
        Csrf searcherCsrf = csrf(searcherSession);

        JsonNode root = objectMapper.readTree(search(searcherSession, searcherCsrf,
            "{\"query\":\"proprietary widget forecast\"}"));

        assertThat(root.get("count").asInt())
            .as("another user's chunks must never surface")
            .isEqualTo(0);
        assertThat(ownerSession).isNotNull();
    }

    @Test
    void searchRequiresAuthentication() throws Exception {
        // Provision a CSRF token (POSTs are rejected with 403 before auth is
        // even considered), then call search with a valid token but no session:
        // that must fail with 401, proving authentication — not CSRF — guards it.
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        Cookie xsrf = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        String token = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/search")
                .cookie(xsrf)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void blankQueryIsRejectedWith400() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        mockMvc.perform(post("/api/v1/search")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Server-side result cap (fused union must be trimmed to the limit)
    // ------------------------------------------------------------------

    @Test
    void resultLimitIsCappedByServerMaximum() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "migration-report.pdf");
        for (int i = 0; i < 8; i++) {
            chunk(doc, i, "Wildebeest migration timing varies across the savanna region, segment " + i + ".");
        }

        // Request far more than verityai.search.max-results=5 for this context
        JsonNode root = objectMapper.readTree(search(session, csrf,
            "{\"query\":\"wildebeest migration\",\"limit\":50}"));

        assertThat(root.get("count").asInt())
            .as("server cap (5) must be enforced even when the client asks for 50")
            .isEqualTo(5);
        assertThat(root.get("results").size()).isEqualTo(5);
    }
}
