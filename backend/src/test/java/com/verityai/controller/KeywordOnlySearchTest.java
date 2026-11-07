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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Feature 4 with {@code verityai.embeddings.provider=off}: the entire
 * pipeline must degrade to pure full-text search — same results for keyword
 * queries, empty results for paraphrases, and an honest {@code model: null}
 * because the semantic branch never ran. This is the offline/graceful-
 * fallback path the README promises.
 */
@SpringBootTest(properties = {
    "verityai.embeddings.provider=off",
    "verityai.upload.dir=target/test-uploads-keyword",
    "verityai.summary.enabled=false",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class KeywordOnlySearchTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;

    private record Csrf(Cookie cookie, String token) {}

    private String uniqueEmail() {
        return "kw-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Keyword Test"))))
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

    @Test
    void keywordQueryStillWorksWithEmbeddingsDisabled() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "reef-survey.pdf");
        chunk(doc, 0, "Marine biologists tracked coral bleaching across the reef system.");
        chunk(doc, 1, "Water temperatures peaked in February along the northern reef.");

        JsonNode root = objectMapper.readTree(search(session, csrf,
            "{\"query\":\"coral bleaching\"}"));

        assertThat(root.get("strategy").asText()).isEqualTo("keyword");
        assertThat(root.get("count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(root.get("model").isNull())
            .as("no embedding model may be reported when the semantic branch never ran")
            .isTrue();

        JsonNode hit = root.at("/results/0");
        assertThat(hit.get("keywordMatch").asBoolean()).isTrue();
        assertThat(hit.get("semanticMatch").asBoolean()).isFalse();
        assertThat(hit.get("similarity").isNull()).isTrue();
        assertThat(hit.get("rank").asDouble()).isGreaterThan(0.0);
        assertThat(hit.get("documentId").asText()).isEqualTo(doc.getId().toString());
    }

    @Test
    void paraphraseReturnsHonestEmptyResultWithoutSemanticBranch() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document doc = document(user(email), "reef-survey-2.pdf");
        chunk(doc, 0, "Marine biologists tracked coral bleaching across the reef system.");

        // No lexical overlap with the chunk — with the semantic branch off there
        // is genuinely nothing to match, and the API must say so (count 0).
        JsonNode root = objectMapper.readTree(search(session, csrf,
            "{\"query\":\"sea creatures suffering from heat\"}"));

        assertThat(root.get("count").asInt()).isEqualTo(0);
        assertThat(root.get("strategy").asText()).isEqualTo("keyword");
        assertThat(root.get("model").isNull()).isTrue();
        assertThat(root.get("results").size()).isEqualTo(0);
    }

    @Test
    void scopedSearchAndValidationStillApplyWithEmbeddingsDisabled() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Document included = document(user(email), "in-scope.pdf");
        chunk(included, 0, "Quarterly gazelle population counts for the valley.");
        Document excluded = document(user(email), "out-of-scope.pdf");
        chunk(excluded, 0, "Quarterly gazelle population counts for the plateau.");

        JsonNode root = objectMapper.readTree(search(session, csrf,
            "{\"query\":\"gazelle population\",\"documentIds\":[\"" + included.getId() + "\"]}"));

        assertThat(root.get("count").asInt()).isEqualTo(1);
        assertThat(root.at("/results/0/documentId").asText()).isEqualTo(included.getId().toString());

        // Blank query is still a validation error (400), not an empty search
        mockMvc.perform(post("/api/v1/search")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\"}"))
            .andExpect(status().isBadRequest());

        assertThat(excluded).isNotNull();
    }
}
