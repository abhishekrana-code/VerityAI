package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.User;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.ai.LlmService;
import com.verityai.service.retrieval.RetrievalService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Phase 2 Features 5 + 6 against a real pgvector PostgreSQL container with a
 * mocked {@link LlmService} (no external API calls from tests):
 *
 * <ul>
 *   <li><b>Multi-document chat:</b> evidence and citations are strictly scoped
 *       to the conversation's documents — a highly-matching document outside
 *       the conversation must never be cited; explicit {@code documentIds}
 *       narrow the scope further; foreign document ids are rejected (403).</li>
 *   <li><b>Claim verification:</b> deterministic layer (uncited claims,
 *       invalid [Sn] references, coverage) runs without a model; the model
 *       layer classifies cited claims; failures report FAILED and store
 *       nothing; results round-trip through GET; re-verification replaces
 *       stored rows; ownership is enforced (404).</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads-rag",
    "verityai.summary.enabled=false",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class ConversationRagIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MessageCitationRepository citationRepository;

    @MockitoBean private LlmService llmService;

    private record Csrf(Cookie cookie, String token) {}

    private String uniqueEmail() {
        return "rag-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "RAG Test"))))
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

    private Conversation conversation(Cookie session, Csrf csrf, String title, List<UUID> documentIds) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        if (documentIds != null && !documentIds.isEmpty()) {
            body.put("documentIds", documentIds);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/conversations")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        return conversationRepository.findById(id).orElseThrow();
    }

    private Message assistantMessage(Conversation conversation, String content) {
        Message m = new Message();
        m.setConversation(conversation);
        m.setRole(Message.MessageRole.ASSISTANT);
        m.setContent(content);
        m.setStatus(Message.MessageStatus.COMPLETED);
        return messageRepository.save(m);
    }

    private Message userMessage(Conversation conversation, String content) {
        Message m = new Message();
        m.setConversation(conversation);
        m.setRole(Message.MessageRole.USER);
        m.setContent(content);
        m.setStatus(Message.MessageStatus.COMPLETED);
        return messageRepository.save(m);
    }

    private MessageCitation cite(Message message, Document document, DocumentChunk chunk, int index) {
        MessageCitation c = new MessageCitation();
        c.setMessage(message);
        c.setChunk(chunk);
        c.setDocument(document); // passed explicitly: detached entities can't lazy-load
        c.setPageNumber(chunk.getPageNumber());
        c.setExcerpt(chunk.getContent());
        c.setRank(0.9f);
        c.setCitationIndex(index);
        return citationRepository.save(c);
    }

    // ==================================================================
    // Feature 5: multi-document chat — citation integrity
    // ==================================================================

    @Test
    void chatCitesOnlyDocumentsInsideTheConversation() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document docA = document(owner, "annual-report.pdf");
        DocumentChunk chunkA = chunk(docA, 0, "Customer churn increased after the pricing change in Q3.");
        Document docB = document(owner, "audit-notes.pdf");
        DocumentChunk chunkB = chunk(docB, 0, "Retention improved when loyalty discounts were offered to long-term customers.");
        // Outside the conversation, and deliberately the BEST lexical match for the question:
        Document outside = document(owner, "unrelated-brief.pdf");
        chunk(outside, 0, "Pricing changes and customer retention: how pricing affects customer retention and churn.");

        Conversation conv = conversation(session, csrf, "Pricing review",
            List.of(docA.getId(), docB.getId()));

        when(llmService.generate(any(LlmService.ChatRequest.class))).thenReturn(
            new LlmService.ChatResponse("Churn rose after the pricing change. [S1]",
                10, 8, 5, "test-model", List.of(0)));

        MvcResult result = mockMvc.perform(post("/api/v1/conversations/" + conv.getId() + "/messages")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"How did pricing changes affect customer retention?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.evidenceStatus").value("SUFFICIENT"))
            .andExpect(jsonPath("$.citations.length()").value(1))
            .andReturn();

        JsonNode citations = objectMapper.readTree(result.getResponse().getContentAsString()).get("citations");
        String citedDoc = citations.get(0).get("documentId").asText();
        assertThat(citedDoc)
            .as("citation must point at a conversation document, never the outside one")
            .isIn(docA.getId().toString(), docB.getId().toString());
        assertThat(citedDoc).isNotEqualTo(outside.getId().toString());
        assertThat(citations.get(0).get("citationIndex").asInt()).isEqualTo(0);
        assertThat(citations.get(0).get("pageNumber").asInt()).isEqualTo(1);
        assertThat(citations.get(0).get("documentName").asText()).isNotBlank();

        // Evidence handed to the model must also be scoped to the conversation
        ArgumentCaptor<LlmService.ChatRequest> captor = ArgumentCaptor.forClass(LlmService.ChatRequest.class);
        verify(llmService).generate(captor.capture());
        Set<UUID> allowedChunkIds = Set.of(chunkA.getId(), chunkB.getId());
        Set<UUID> evidenceDocIds = new java.util.HashSet<>();
        for (RetrievalService.RetrievedChunk rc : captor.getValue().evidence()) {
            assertThat(rc.chunk().getId()).isIn(allowedChunkIds);
            evidenceDocIds.add(rc.chunk().getId().equals(chunkA.getId()) ? docA.getId() : docB.getId());
        }
        assertThat(evidenceDocIds).isNotEmpty().isSubsetOf(docA.getId(), docB.getId());

        // The conversation detail exposes the context chips the UI renders
        mockMvc.perform(get("/api/v1/conversations/" + conv.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documents.length()").value(2));
    }

    @Test
    void explicitDocumentIdsNarrowEvidenceToRequestedDocuments() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document docA = document(owner, "scope-a.pdf");
        DocumentChunk chunkA = chunk(docA, 0, "The alpha budget was approved by the committee.");
        Document docB = document(owner, "scope-b.pdf");
        DocumentChunk chunkB = chunk(docB, 0, "The beta budget was rejected by the committee.");

        Conversation conv = conversation(session, csrf, "Scope test", List.of(docA.getId(), docB.getId()));

        when(llmService.generate(any(LlmService.ChatRequest.class))).thenReturn(
            new LlmService.ChatResponse("Approved. [S1]", 10, 8, 5, "test-model", List.of(0)));

        mockMvc.perform(post("/api/v1/conversations/" + conv.getId() + "/messages")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Was the budget approved?\",\"documentIds\":[\"" + docA.getId() + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.citations[0].documentId").value(docA.getId().toString()));

        ArgumentCaptor<LlmService.ChatRequest> captor = ArgumentCaptor.forClass(LlmService.ChatRequest.class);
        verify(llmService).generate(captor.capture());
        Set<UUID> evidenceIds = captor.getValue().evidence().stream()
            .map(rc -> rc.chunk().getId())
            .collect(java.util.stream.Collectors.toSet());
        assertThat(evidenceIds).containsExactly(chunkA.getId()); // docB excluded despite being in the conversation
        assertThat(evidenceIds).doesNotContain(chunkB.getId());
    }

    @Test
    void chatRejectsForeignDocumentIdsWith403() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Document foreign = document(user(ownerEmail), "foreign.pdf");
        chunk(foreign, 0, "Belongs to someone else entirely.");

        String askerEmail = uniqueEmail();
        Cookie askerSession = register(askerEmail);
        Csrf askerCsrf = csrf(askerSession);
        Conversation conv = conversation(askerSession, askerCsrf, "Cross-user attempt", null);

        mockMvc.perform(post("/api/v1/conversations/" + conv.getId() + "/messages")
                .cookie(askerSession, askerCsrf.cookie())
                .header("X-XSRF-TOKEN", askerCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"What is in this document?\",\"documentIds\":[\"" + foreign.getId() + "\"]}"))
            .andExpect(status().isForbidden());

        assertThat(ownerSession).isNotNull();
    }

    // ==================================================================
    // Feature 6: claim verification
    // ==================================================================

    private String verifyPath(Conversation conv, Message message) {
        return "/api/v1/conversations/" + conv.getId() + "/messages/" + message.getId() + "/verify";
    }

    @Test
    void getBeforeAnyRunReportsNotRequested() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document source = document(owner, "source.pdf");
        DocumentChunk sourceChunk = chunk(source, 0, "Revenue grew 14 percent in 2024.");
        Conversation conv = conversation(session, csrf, "Verify get", null);
        Message message = assistantMessage(conv, "Revenue grew 14 percent in 2024. [S1]");
        cite(message, source, sourceChunk, 0);

        mockMvc.perform(get(verifyPath(conv, message)).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NOT_REQUESTED"))
            .andExpect(jsonPath("$.claims").isEmpty())
            .andExpect(jsonPath("$.invalidReferences").isEmpty())
            .andExpect(jsonPath("$.coverage").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.model").value(org.hamcrest.Matchers.nullValue()));

        verify(llmService, never()).complete(any());
    }

    @Test
    void uncitedMessageClassifiesDeterministicallyWithoutModelCall() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Conversation conv = conversation(session, csrf, "Verify uncited", null);
        Message message = assistantMessage(conv,
            "Revenue grew 14 percent in 2024. Costs fell 3 percent that year.");

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.coverage").value(0.0))
            .andExpect(jsonPath("$.model").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.claims.length()").value(2))
            .andExpect(jsonPath("$.claims[0].supportClass").value("UNSUPPORTED"))
            .andExpect(jsonPath("$.claims[1].supportClass").value("UNSUPPORTED"))
            .andExpect(jsonPath("$.claims[0].explanation").value("No source is cited for this claim."));

        // No citation anywhere → nothing for a model to judge; never call it
        verify(llmService, never()).complete(any());

        // …and the result is readable afterwards
        mockMvc.perform(get(verifyPath(conv, message)).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.claims.length()").value(2));
    }

    @Test
    void citedClaimsClassifyWithModelAndRoundTripThroughGet() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document source = document(owner, "source-roundtrip.pdf");
        DocumentChunk sourceChunk = chunk(source, 0, "Revenue grew 14 percent in 2024.");
        Conversation conv = conversation(session, csrf, "Verify cited", null);
        // [S1] must sit inside the FIRST sentence so claim 0 is the cited one
        // the mocked model classifies (claim 1 stays uncited → deterministic).
        Message message = assistantMessage(conv,
            "Revenue grew 14 percent in 2024 [S1]. Costs fell 3 percent that year.");
        cite(message, source, sourceChunk, 0);

        when(llmService.complete(any(LlmService.CompletionRequest.class))).thenReturn(
            new LlmService.CompletionResponse(
                "[{\"i\":0,\"class\":\"SUPPORTED\",\"why\":\"the excerpt states the figure\"}]",
                20, 12, 7, "test-model"));

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.coverage").value(0.5)) // 1 of 2 claims cites evidence
            .andExpect(jsonPath("$.claims.length()").value(2))
            .andExpect(jsonPath("$.claims[0].supportClass").value("SUPPORTED"))
            .andExpect(jsonPath("$.claims[0].citationIndexes[0]").value(0))
            .andExpect(jsonPath("$.claims[0].explanation").value("the excerpt states the figure"))
            .andExpect(jsonPath("$.claims[1].supportClass").value("UNSUPPORTED"))
            .andExpect(jsonPath("$.invalidReferences").isEmpty());

        // GET returns the stored result without calling the model again
        mockMvc.perform(get(verifyPath(conv, message)).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.coverage").value(0.5))
            .andExpect(jsonPath("$.claims[0].supportClass").value("SUPPORTED"));

        verify(llmService, never()).generate(any());
    }

    @Test
    void invalidCitationReferenceIsReportedWithoutModelCall() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document source = document(owner, "source-invalid.pdf");
        DocumentChunk sourceChunk = chunk(source, 0, "The merger closed in March.");
        Conversation conv = conversation(session, csrf, "Verify invalid ref", null);
        Message message = assistantMessage(conv, "The merger closed in March [S7] as planned.");
        cite(message, source, sourceChunk, 0); // only S1 exists — [S7] is invalid

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.coverage").value(0.0))
            .andExpect(jsonPath("$.invalidReferences.length()").value(1))
            .andExpect(jsonPath("$.invalidReferences[0].reference").value(7))
            .andExpect(jsonPath("$.claims[0].hasInvalidCitation").value(true))
            .andExpect(jsonPath("$.claims[0].citationIndexes").isEmpty())
            .andExpect(jsonPath("$.claims[0].supportClass").value("UNSUPPORTED"));

        // Invalid-only citation means nothing valid to judge → deterministic, no model
        verify(llmService, never()).complete(any());
    }

    @Test
    void modelFailureReportsFailedAndStoresNothing() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document source = document(owner, "source-fail.pdf");
        DocumentChunk sourceChunk = chunk(source, 0, "Revenue grew 14 percent in 2024.");
        Conversation conv = conversation(session, csrf, "Verify fail", null);
        // Single cited claim: the model must run — and when it fails, status is
        // FAILED with nothing stored (an uncited claim would classify without
        // the model and mask the failure).
        Message message = assistantMessage(conv, "Revenue grew 14 percent in 2024 [S1].");
        cite(message, source, sourceChunk, 0);

        when(llmService.complete(any(LlmService.CompletionRequest.class)))
            .thenThrow(new RuntimeException("simulated outage"));

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("simulated outage")));

        // Nothing stored → the message stays NOT_REQUESTED for a clean retry
        mockMvc.perform(get(verifyPath(conv, message)).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NOT_REQUESTED"))
            .andExpect(jsonPath("$.claims").isEmpty());
    }

    @Test
    void reverificationReplacesStoredResultsInsteadOfDuplicating() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);
        User owner = user(email);

        Document source = document(owner, "source-reverify.pdf");
        DocumentChunk sourceChunk = chunk(source, 0, "Revenue grew 14 percent in 2024.");
        Conversation conv = conversation(session, csrf, "Verify re-run", null);
        Message message = assistantMessage(conv, "Revenue grew 14 percent in 2024 [S1].");
        cite(message, source, sourceChunk, 0);

        when(llmService.complete(any(LlmService.CompletionRequest.class))).thenReturn(
            new LlmService.CompletionResponse("[{\"i\":0,\"class\":\"SUPPORTED\",\"why\":\"first pass\"}]", 10, 5, 4, "test-model"),
            new LlmService.CompletionResponse("[{\"i\":0,\"class\":\"CONFLICTING\",\"why\":\"second pass\"}]", 10, 5, 4, "test-model"));

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claims[0].supportClass").value("SUPPORTED"));

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claims.length()").value(1))
            .andExpect(jsonPath("$.claims[0].supportClass").value("CONFLICTING"))
            .andExpect(jsonPath("$.claims[0].explanation").value("second pass"));
    }

    @Test
    void foreignUserCannotReadOrRunVerification() throws Exception {
        String ownerEmail = uniqueEmail();
        Cookie ownerSession = register(ownerEmail);
        Csrf ownerCsrf = csrf(ownerSession);
        User owner = user(ownerEmail);

        Document source = document(owner, "private-source.pdf");
        DocumentChunk sourceChunk = chunk(source, 0, "Revenue grew 14 percent in 2024.");
        Conversation conv = conversation(ownerSession, ownerCsrf, "Private conversation", null);
        Message message = assistantMessage(conv, "Revenue grew 14 percent in 2024. [S1]");
        cite(message, source, sourceChunk, 0);

        String intruderEmail = uniqueEmail();
        Cookie intruderSession = register(intruderEmail);
        Csrf intruderCsrf = csrf(intruderSession);

        mockMvc.perform(get(verifyPath(conv, message)).cookie(intruderSession))
            .andExpect(status().isNotFound());

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(intruderSession, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token()))
            .andExpect(status().isNotFound());
    }

    @Test
    void verificationOfUserMessagesIsRejected() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);
        Csrf csrf = csrf(session);

        Conversation conv = conversation(session, csrf, "Verify user msg", null);
        Message message = userMessage(conv, "What did the report say about revenue?");

        mockMvc.perform(post(verifyPath(conv, message))
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isBadRequest());
    }
}
