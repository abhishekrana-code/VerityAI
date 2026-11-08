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

    private record Csrf(Cookie cookie, String token) {
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String uniqueEmail() {
        return "intel-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Intel Test"))))
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

    private void event(Document document, int attempt, DocumentProcessingEvent.Stage stage,
                       Instant createdAt, String errorCode, String errorMessage) {
        DocumentProcessingEvent event = new DocumentProcessingEvent();
        event.setDocument(document);
        event.setAttempt(attempt);
        event.setStage(stage);
        event.setCreatedAt(createdAt);
        event.setErrorCode(errorCode);
        event.setErrorMessage(errorMessage);
        eventRepository.save(event);
    }

    // ------------------------------------------------------------------
    // Knowledge graph
    // ------------------------------------------------------------------

    @Test
    void knowledgeGraphIsBuiltFromChunksAndSummaryEntities() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

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

        mockMvc.perform(post("/api/v1/knowledge-graph/rebuild")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + document.getId() + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rebuilt").value(1))
            .andExpect(jsonPath("$.requested").value(1));

        MvcResult result = mockMvc.perform(get("/api/v1/knowledge-graph")
                .cookie(session)
                .param("documentId", document.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("DOCUMENT"))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.documentName").value("annual-report.pdf"))
            .andExpect(jsonPath("$.generatedAt").isNotEmpty())
            .andReturn();

        JsonNode graph = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(graph.get("entityCount").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(graph.get("relationCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(graph.get("nodes").isArray()).isTrue();

        List<String> names = new java.util.ArrayList<>();
        graph.get("nodes").forEach(node -> names.add(node.get("name").asText()));
        assertThat(names).contains("Alpha Corp", "Beta Systems", "Project Orion");

        JsonNode edge = graph.at("/edges/0");
        assertThat(edge.get("relationType").asText()).isEqualTo("CO_OCCURS");
        assertThat(edge.get("weight").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(edge.get("source").asText()).isNotBlank();
        assertThat(edge.get("target").asText()).isNotBlank();

        // The summary model's type must win over the pattern fallback.
        JsonNode beta = findNode(graph, "Beta Systems");
        assertThat(beta).as("Beta Systems node").isNotNull();
        assertThat(beta.get("type").asText()).isEqualTo("ORGANIZATION");

        // Type filter returns only that type.
        MvcResult filtered = mockMvc.perform(get("/api/v1/knowledge-graph")
                .cookie(session)
                .param("documentId", document.getId().toString())
                .param("type", "ORGANIZATION"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode filteredGraph = objectMapper.readTree(filtered.getResponse().getContentAsString());
        assertThat(filteredGraph.get("nodes").size()).isGreaterThanOrEqualTo(2);
        filteredGraph.get("nodes").forEach(node ->
            assertThat(node.get("type").asText()).isEqualTo("ORGANIZATION"));
    }

    @Test
    void knowledgeGraphIsOwnershipScoped() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Csrf ownerCsrf = csrf(ownerSession);
        Document owned = document(user(ownerEmail), "private-graph.pdf");
        chunk(owned, 0, 1, "Secret Project Falcon was discussed by Acme Corp in 2025.");

        mockMvc.perform(post("/api/v1/knowledge-graph/rebuild")
                .cookie(ownerSession, ownerCsrf.cookie())
                .header("X-XSRF-TOKEN", ownerCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + owned.getId() + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rebuilt").value(1));

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);

        mockMvc.perform(get("/api/v1/knowledge-graph")
                .cookie(intruderSession)
                .param("documentId", owned.getId().toString()))
            .andExpect(status().isNotFound());

        MvcResult library = mockMvc.perform(get("/api/v1/knowledge-graph").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("LIBRARY"))
            .andReturn();
        JsonNode graph = objectMapper.readTree(library.getResponse().getContentAsString());
        assertThat(graph.get("entityCount").asLong()).isZero();
        assertThat(graph.get("nodes").size()).isZero();
    }

    @Test
    void knowledgeGraphRejectsUnknownEntityType() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        mockMvc.perform(get("/api/v1/knowledge-graph")
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
    // Report index
    // ------------------------------------------------------------------

    @Test
    void reportsExposePersistedSummarySections() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        Document document = document(user(email), "annual-report.pdf");
        document.setSummaryStatus(Document.SummaryStatus.READY);
        documentRepository.save(document);

        summary(document, DocumentSummary.Kind.EXECUTIVE_SUMMARY,
            "{\"summary\":\"Revenue grew 42% in 2023 and margins improved.\"}");
        summary(document, DocumentSummary.Kind.KEY_FINDINGS, """
            {"findings":[
              {"text":"Revenue grew 42% in 2023.","type":"FACT"},
              {"text":"Growth is likely to continue.","type":"INTERPRETATION"}
            ]}
            """);
        summary(document, DocumentSummary.Kind.ENTITIES,
            "{\"entities\":[{\"name\":\"Alpha Corp\",\"type\":\"ORGANIZATION\"}]}");
        summary(document, DocumentSummary.Kind.DATES_AND_STATS,
            "{\"items\":[{\"value\":\"2023\"}]}");
        summary(document, DocumentSummary.Kind.TOPICS,
            "{\"topics\":[\"finance\",\"growth\"]}");
        summary(document, DocumentSummary.Kind.SUGGESTED_QUESTIONS,
            "{\"questions\":[\"What drove growth?\"]}");

        MvcResult result = mockMvc.perform(get("/api/v1/reports")
                .cookie(session)
                .param("q", "annual-report"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andReturn();
        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsString()).at("/content/0");

        assertThat(item.get("documentId").asText()).isEqualTo(document.getId().toString());
        assertThat(item.get("documentName").asText()).isEqualTo("annual-report.pdf");
        assertThat(item.get("summaryStatus").asText()).isEqualTo("READY");
        assertThat(item.get("executiveSummary").asText()).contains("42%");
        assertThat(item.get("sectionCount").asInt()).isEqualTo(6);
        assertThat(item.get("model").asText()).isEqualTo("test-summary-model");
        assertThat(item.get("entityCount").asInt()).isEqualTo(1);
        assertThat(item.get("dateStatCount").asInt()).isEqualTo(1);
        assertThat(item.get("topicCount").asInt()).isEqualTo(2);
        assertThat(item.get("questionCount").asInt()).isEqualTo(1);
        assertThat(item.get("keyFindings").size()).isEqualTo(2);
        assertThat(item.at("/keyFindings/0/type").asText()).isEqualTo("FACT");
        assertThat(item.at("/keyFindings/1/type").asText()).isEqualTo("INTERPRETATION");

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);
        mockMvc.perform(get("/api/v1/reports").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // History feed
    // ------------------------------------------------------------------

    @Test
    void historyListsProcessingEventsNewestFirst() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Document document = document(user(email), "history-report.pdf");

        Instant now = Instant.now();
        event(document, 1, DocumentProcessingEvent.Stage.QUEUED, now.minus(3, ChronoUnit.MINUTES), null, null);
        event(document, 1, DocumentProcessingEvent.Stage.FAILED, now.minus(2, ChronoUnit.MINUTES),
            "EXTRACTION_FAILED", "The PDF could not be read.");
        event(document, 2, DocumentProcessingEvent.Stage.COMPLETED, now.minus(1, ChronoUnit.MINUTES), null, null);

        MvcResult result = mockMvc.perform(get("/api/v1/history/processing")
                .cookie(session)
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andReturn();
        JsonNode history = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(history.get("content").size()).isEqualTo(3);
        assertThat(history.at("/content/0/stage").asText()).isEqualTo("COMPLETED");
        assertThat(history.at("/content/0/attempt").asInt()).isEqualTo(2);
        assertThat(history.at("/content/1/stage").asText()).isEqualTo("FAILED");
        assertThat(history.at("/content/1/errorCode").asText()).isEqualTo("EXTRACTION_FAILED");
        assertThat(history.at("/content/1/errorMessage").asText()).contains("could not be read");
        assertThat(history.at("/content/0/documentName").asText()).isEqualTo("history-report.pdf");

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);
        mockMvc.perform(get("/api/v1/history/processing").cookie(intruderSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    @Test
    void intelligenceReadModelsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-graph")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/verifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/verifications/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/history/processing")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private static JsonNode findNode(JsonNode graph, String name) {
        for (JsonNode node : graph.get("nodes")) {
            if (name.equals(node.get("name").asText())) {
                return node;
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
