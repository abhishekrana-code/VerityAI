package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.DocumentProcessingEvent;
import com.verityai.entity.DocumentSummary;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.MessageClaimVerification;
import com.verityai.entity.MessageClaimVerification.SupportClass;
import com.verityai.entity.User;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentProcessingEventRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageClaimVerificationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.repository.UserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 intelligence pages: knowledge graph, verification index, report
 * index and processing-history feed.
 *
 * <p>Data is seeded directly in PostgreSQL so the assertions test the read
 * models and the deterministic graph extraction, not the upload pipeline or
 * any model. Every endpoint is ownership-scoped and requires authentication.</p>
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads-intel-pages",
    "verityai.rate-limit.register-per-hour-ip=1000",
    "verityai.summary.enabled=false",
    "verityai.embeddings.provider=off",
    "verityai.knowledge.backfill-limit=0",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class IntelligencePagesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private DocumentSummaryRepository summaryRepository;
    @Autowired private DocumentProcessingEventRepository eventRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MessageCitationRepository citationRepository;
    @Autowired private MessageClaimVerificationRepository verificationRepository;
    @Autowired private com.verityai.service.knowledge.KnowledgeGraphService knowledgeGraphService;

    private record Csrf(Cookie cookie, String token) {
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String uniqueEmail() {
        return "intel-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Intel Test"))))
            .andExpect(status().isOk());
        return login(email);
    }

    private Cookie login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Intel Test"))))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie after login").isNotNull();
        return session;
    }

    private static String uniqueIp() {
        java.util.concurrent.ThreadLocalRandom random =
            java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255)
            + "." + random.nextInt(1, 255);
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

    private Document document(User owner, String filename) {
        Document document = new Document();
        document.setUser(owner);
        document.setOriginalFilename(filename);
        document.setStoredFilename("stored-" + UUID.randomUUID() + "-" + filename);
        document.setMimeType("application/pdf");
        document.setSizeBytes(2048L);
        document.setSha256((UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "")).substring(0, 64));
        document.setStatus(Document.DocumentStatus.READY);
        return documentRepository.save(document);
    }

    private DocumentChunk chunk(Document document, int index, int page, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setPageNumber(page);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunkRepository.save(chunk);
    }

    private DocumentSummary summary(Document document, DocumentSummary.Kind kind, String json) throws Exception {
        DocumentSummary summary = new DocumentSummary();
        summary.setDocument(document);
        summary.setKind(kind);
        summary.setSource(kind == DocumentSummary.Kind.EXECUTIVE_SUMMARY
            || kind == DocumentSummary.Kind.KEY_FINDINGS
            ? DocumentSummary.Source.GENERATED : DocumentSummary.Source.EXTRACTED);
        summary.setContent(objectMapper.readTree(json));
        summary.setModel("test-summary-model");
        return summaryRepository.save(summary);
    }

    private Conversation conversation(User owner, String title) {
        Conversation conversation = new Conversation();
        conversation.setUser(owner);
        conversation.setTitle(title);
        return conversationRepository.save(conversation);
    }

    private Message assistantMessage(Conversation conversation, String content) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(Message.MessageRole.ASSISTANT);
        message.setContent(content);
        message.setStatus(Message.MessageStatus.COMPLETED);
        message.setModel("test-chat-model");
        message.setEvidenceStatus(Message.EvidenceStatus.SUFFICIENT);
        return messageRepository.save(message);
    }

    private void citation(Message message, DocumentChunk chunk, int citationIndex) {
        MessageCitation citation = new MessageCitation();
        citation.setMessage(message);
        citation.setChunk(chunk);
        citation.setDocument(chunk.getDocument());
        citation.setPageNumber(chunk.getPageNumber());
        citation.setExcerpt(chunk.getContent());
        citation.setRank(0.9f);
        citation.setCitationIndex(citationIndex);
        citationRepository.save(citation);
    }

    private void verificationRow(Message message, int claimIndex, String claimText,
                                 SupportClass supportClass, List<Integer> citationIndexes) {
        MessageClaimVerification row = new MessageClaimVerification();
        row.setMessage(message);
        row.setClaimIndex(claimIndex);
        row.setSentenceIndex(claimIndex);
        row.setClaimText(claimText);
        row.setSupportClass(supportClass);
        row.setExplanation("test explanation");
        row.setCitationIndexes(citationIndexes);
        row.setModel("test-verifier");
        row.setVerifiedAt(Instant.now());
        verificationRepository.save(row);
    }

    // ------------------------------------------------------------------
    // Entity index and document connections
    // ------------------------------------------------------------------

    @Test
    void entityIndexMergesChunksAndSummaryEntities() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        Document document = document(user(email), "annual-report.pdf");
        chunk(document, 0, 1,
            "Alpha Corp reported revenue growth of 42% in 2023. "
                + "Beta Systems and Project Orion were mentioned together. "
                + "The team met Alpha Corp in Berlin.");
        chunk(document, 1, 2,
            "Beta Systems delivered Project Orion on schedule in 2024.");
        summary(document, DocumentSummary.Kind.ENTITIES, """
            {"entities":[
              {"name":"Beta Systems","type":"ORGANIZATION"},
              {"name":"Project Orion","type":"PRODUCT"}
            ]}
            """);

        // Extraction is deterministic and runs on processing; forcing it here
        // stands in for the READY transition.
        assertThat(knowledgeGraphService.rebuild(document.getId())).isTrue();

        MvcResult result = mockMvc.perform(get("/api/v1/knowledge-graph/entities")
                .cookie(session)
                .param("documentId", document.getId().toString()))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode index = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(index.get("totalEntities").asInt()).isGreaterThanOrEqualTo(5);

        List<String> names = new java.util.ArrayList<>();
        index.get("entities").forEach(item -> names.add(item.get("name").asText()));
        assertThat(names).contains("Alpha Corp", "Beta Systems", "Project Orion");

        // The summary model's type must win over the pattern fallback.
        JsonNode beta = findEntity(index, "Beta Systems");
        assertThat(beta).as("Beta Systems row").isNotNull();
        assertThat(beta.get("type").asText()).isEqualTo("ORGANIZATION");
        assertThat(beta.get("evidenceExcerpt").asText()).isNotBlank();

        // Type filter returns only that type.
        MvcResult filtered = mockMvc.perform(get("/api/v1/knowledge-graph/entities")
                .cookie(session)
                .param("documentId", document.getId().toString())
                .param("type", "ORGANIZATION"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode filteredIndex = objectMapper.readTree(filtered.getResponse().getContentAsString());
        assertThat(filteredIndex.get("entities").size()).isGreaterThanOrEqualTo(2);
        filteredIndex.get("entities").forEach(item ->
            assertThat(item.get("type").asText()).isEqualTo("ORGANIZATION"));
    }

    @Test
    void entityIndexIsOwnershipScoped() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Document owned = document(user(ownerEmail), "private-graph.pdf");
        chunk(owned, 0, 1, "Secret Project Falcon was discussed by Acme Corp in 2025.");
        assertThat(knowledgeGraphService.rebuild(owned.getId())).isTrue();

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);

        mockMvc.perform(get("/api/v1/knowledge-graph/entities")
                .cookie(intruderSession)
                .param("documentId", owned.getId().toString()))
            .andExpect(status().isNotFound());

        MvcResult library = mockMvc.perform(get("/api/v1/knowledge-graph/entities").cookie(intruderSession))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode index = objectMapper.readTree(library.getResponse().getContentAsString());
        assertThat(index.get("totalEntities").asInt()).isZero();
        assertThat(index.get("entities").size()).isZero();

        assertThat(ownerSession).isNotNull();
    }

    @Test
    void entityIndexRejectsUnknownEntityType() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        mockMvc.perform(get("/api/v1/knowledge-graph/entities")
                .cookie(session)
                .param("type", "BANANA"))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Verification index
    // ------------------------------------------------------------------

    @Test
    void verificationIndexReportsStoredClaimResults() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        Document document = document(user(email), "verified-source.pdf");
        DocumentChunk first = chunk(document, 0, 1, "Revenue grew 42% in 2023.");
        DocumentChunk second = chunk(document, 1, 2, "Costs fell by 3%.");

        Conversation completedConversation = conversation(user(email), "Completed session");
        Message completed = assistantMessage(completedConversation,
            "Revenue grew 42% in 2023 [S1]. Costs fell by 3% [S2]. Margins remained flat.");
        citation(completed, first, 0);
        citation(completed, second, 1);
        verificationRow(completed, 0, "Revenue grew 42% in 2023 [S1].",
            SupportClass.SUPPORTED, List.of(0));
        verificationRow(completed, 1, "Costs fell by 3% [S2].",
            SupportClass.UNSUPPORTED, List.of(1));
        // Deterministic layer marks a claim that cites nothing UNSUPPORTED with
        // no citation indexes, so it lowers coverage but is still classified.
        verificationRow(completed, 2, "Margins remained flat.",
            SupportClass.UNSUPPORTED, List.of());

        Conversation partialConversation = conversation(user(email), "Partial session");
        Message partial = assistantMessage(partialConversation,
            "Margins improved in 2024 [S1].");
        citation(partial, first, 0);
        verificationRow(partial, 0, "Margins improved in 2024 [S1].", null, List.of(0));

        MvcResult listResult = mockMvc.perform(get("/api/v1/verifications")
                .cookie(session)
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());

        JsonNode completedItem = findVerification(list, completed.getId());
        assertThat(completedItem).as("completed message in verification list").isNotNull();
        assertThat(completedItem.get("status").asText()).isEqualTo("COMPLETED");
        // Two of three analyzed claims cite a source, so coverage is 2/3.
        assertThat(completedItem.get("coverage").asDouble()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(completedItem.get("analyzedClaims").asInt()).isEqualTo(3);
        assertThat(completedItem.get("classifiedClaims").asInt()).isEqualTo(3);
        assertThat(completedItem.get("citationCount").asInt()).isEqualTo(2);
        assertThat(completedItem.get("invalidReferenceCount").asInt()).isZero();
        assertThat(completedItem.at("/supportCounts/SUPPORTED").asInt()).isEqualTo(1);
        assertThat(completedItem.at("/supportCounts/UNSUPPORTED").asInt()).isEqualTo(2);
        assertThat(completedItem.get("totalClaims").asInt()).isGreaterThanOrEqualTo(3);

        JsonNode partialItem = findVerification(list, partial.getId());
        assertThat(partialItem).as("partial message in verification list").isNotNull();
        assertThat(partialItem.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(partialItem.get("classifiedClaims").asInt()).isZero();

        mockMvc.perform(get("/api/v1/verifications")
                .cookie(session)
                .param("status", "COMPLETED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].messageId").value(completed.getId().toString()));

        mockMvc.perform(get("/api/v1/verifications")
                .cookie(session)
                .param("status", "UNVERIFIED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/verifications")
                .cookie(session)
                .param("status", "NONSENSE"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/verifications/stats").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.verified").value(2))
            .andExpect(jsonPath("$.completed").value(1))
            .andExpect(jsonPath("$.partial").value(1))
            .andExpect(jsonPath("$.unverified").value(0));
    }

    @Test
    void verificationIndexNeverLeaksAnotherUsersMessages() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Document document = document(user(ownerEmail), "owner-source.pdf");
        DocumentChunk chunk = chunk(document, 0, 1, "Owner-only evidence.");
        Conversation conversation = conversation(user(ownerEmail), "Owner session");
        Message message = assistantMessage(conversation, "Owner-only claim [S1].");
        citation(message, chunk, 0);
        verificationRow(message, 0, "Owner-only claim [S1].", SupportClass.SUPPORTED, List.of(0));

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);

        mockMvc.perform(get("/api/v1/verifications").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/verifications/stats").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        assertThat(ownerSession).isNotNull();
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    @Test
    void intelligenceReadModelsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-graph/entities")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/knowledge-graph/connections")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/verifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/verifications/stats")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private static JsonNode findEntity(JsonNode index, String name) {
        for (JsonNode item : index.get("entities")) {
            if (name.equals(item.get("name").asText())) {
                return item;
            }
        }
        return null;
    }

    private static JsonNode findVerification(JsonNode list, UUID messageId) {
        for (JsonNode item : list.get("content")) {
            if (messageId.toString().equals(item.get("messageId").asText())) {
                return item;
            }
        }
        return null;
    }
}
