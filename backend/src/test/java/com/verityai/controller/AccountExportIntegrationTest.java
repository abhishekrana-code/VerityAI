package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Contradiction;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.MessageClaimVerification;
import com.verityai.entity.User;
import com.verityai.repository.ContradictionRepository;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export contract: one JSON document per account, containing that account's
 * documents, conversations, messages with citations, and its check results —
 * and nobody else's.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.register-per-hour-ip=1000",
    "logging.level.org.hibernate.SQL=WARN"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountExportIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final String EXCERPT = "Revenue grew 42% in 2023.";
    private static final String CLAIM = "Revenue grew 42%.";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MessageCitationRepository citationRepository;
    @Autowired private MessageClaimVerificationRepository verificationRepository;
    @Autowired private ContradictionRepository contradictionRepository;

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", PASSWORD,
                    "displayName", "Export Me"))))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        return session;
    }

    private User user(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private Document document(User owner, String filename) {
        Document document = new Document();
        document.setUser(owner);
        document.setOriginalFilename(filename);
        document.setStoredFilename("export-" + UUID.randomUUID() + ".pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(1024L);
        document.setPageCount(2);
        document.setSha256((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
        document.setStatus(Document.DocumentStatus.READY);
        return documentRepository.save(document);
    }

    private DocumentChunk chunk(Document document, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setPageNumber(3);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        return chunkRepository.save(chunk);
    }

    private Message answer(User owner, String content) {
        Conversation conversation = new Conversation();
        conversation.setUser(owner);
        conversation.setTitle("Export chat");
        conversation = conversationRepository.save(conversation);

        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(Message.MessageRole.ASSISTANT);
        message.setContent(content);
        message.setModel("test-chat-model");
        message.setEvidenceStatus(Message.EvidenceStatus.SUFFICIENT);
        return messageRepository.save(message);
    }

    private void citation(Message message, DocumentChunk chunk) {
        MessageCitation citation = new MessageCitation();
        citation.setMessage(message);
        citation.setChunk(chunk);
        citation.setDocument(chunk.getDocument());
        citation.setPageNumber(chunk.getPageNumber());
        citation.setExcerpt(chunk.getContent());
        citation.setRank(0.9f);
        citation.setCitationIndex(0);
        citationRepository.save(citation);
    }

    private void claimCheck(Message message) {
        MessageClaimVerification row = new MessageClaimVerification();
        row.setMessage(message);
        row.setClaimIndex(0);
        row.setSentenceIndex(0);
        row.setClaimText(CLAIM);
        row.setSupportClass(MessageClaimVerification.SupportClass.PARTIALLY_SUPPORTED);
        row.setExplanation("The figure matches the excerpt but the year is not stated.");
        row.setCitationIndexes(List.of(0));
        row.setModel("test-verifier");
        row.setVerifiedAt(Instant.now());
        verificationRepository.save(row);
    }

    private void contradiction(User owner, Document first, Document second) {
        Contradiction row = new Contradiction();
        row.setUser(owner);
        row.setDocumentA(first);
        row.setDocumentB(second);
        row.setClaimA("Revenue grew 42% in 2023.");
        row.setClaimB("Revenue grew 12% in 2023.");
        row.setPageA(3);
        row.setPageB(4);
        row.setSubjectEntity("Revenue");
        row.setContradictionType(Contradiction.ContradictionType.NUMERIC_CONFLICT);
        row.setSeverity(Contradiction.Severity.HIGH);
        row.setExplanation("The two reports state different growth figures.");
        row.setModel("test-classifier");
        row.setPairKey((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
        row.setDetectedAt(Instant.now());
        contradictionRepository.save(row);
    }

    @Test
    void exportsTheCallersDocumentsConversationsAndCheckResultsOnly() throws Exception {
        String email = "export-" + UUID.randomUUID() + "@example.com";
        Cookie session = register(email);
        User owner = user(email);

        Document mine = document(owner, "mine.pdf");
        Document appendix = document(owner, "appendix.pdf");
        Message answer = answer(owner, "Revenue grew 42% in 2023 according to the report.");
        citation(answer, chunk(mine, EXCERPT));
        claimCheck(answer);
        contradiction(owner, mine, appendix);

        // A second account with its own document and message: neither may appear.
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        register(otherEmail);
        User other = user(otherEmail);
        document(other, "theirs.pdf");
        answer(other, "Their private question");

        MvcResult result = mockMvc.perform(get("/api/v1/users/me/export").cookie(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.formatVersion").value("1"))
            .andExpect(jsonPath("$.account.email").value(email))
            .andExpect(jsonPath("$.documents.length()").value(2))
            .andExpect(jsonPath("$.documents[0].filename").value("mine.pdf"))
            .andExpect(jsonPath("$.conversations.length()").value(1))
            .andExpect(jsonPath("$.conversations[0].messages.length()").value(1))
            // Citation: the cited document's filename and the excerpt both survive.
            .andExpect(jsonPath("$.conversations[0].messages[0].citations.length()").value(1))
            .andExpect(jsonPath("$.conversations[0].messages[0].citations[0].documentFilename")
                .value("mine.pdf"))
            .andExpect(jsonPath("$.conversations[0].messages[0].citations[0].pageNumber").value(3))
            .andExpect(jsonPath("$.conversations[0].messages[0].citations[0].excerpt").value(EXCERPT))
            // Claim check: claim text and support class.
            .andExpect(jsonPath("$.checks.claims.length()").value(1))
            .andExpect(jsonPath("$.checks.claims[0].claim").value(CLAIM))
            .andExpect(jsonPath("$.checks.claims[0].supportClass").value("PARTIALLY_SUPPORTED"))
            .andExpect(jsonPath("$.checks.claims[0].messageId").value(answer.getId().toString()))
            // Contradiction: both filenames, the claim text and the severity.
            .andExpect(jsonPath("$.checks.contradictions.length()").value(1))
            .andExpect(jsonPath("$.checks.contradictions[0].documentA").value("mine.pdf"))
            .andExpect(jsonPath("$.checks.contradictions[0].documentB").value("appendix.pdf"))
            .andExpect(jsonPath("$.checks.contradictions[0].claimA").value("Revenue grew 42% in 2023."))
            .andExpect(jsonPath("$.checks.contradictions[0].claimB").value("Revenue grew 12% in 2023."))
            .andExpect(jsonPath("$.checks.contradictions[0].severity").value("HIGH"))
            .andExpect(jsonPath("$.checks.contradictions[0].type").value("NUMERIC_CONFLICT"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("theirs.pdf").doesNotContain("Their private question");

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("exportedAt").asText()).isNotBlank();
    }
}
