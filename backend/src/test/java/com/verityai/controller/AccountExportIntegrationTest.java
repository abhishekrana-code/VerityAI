package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.User;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentRepository;
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
 * documents, conversations and messages — and nobody else's.
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

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;

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

    private Message message(User owner, String content) {
        Conversation conversation = new Conversation();
        conversation.setUser(owner);
        conversation.setTitle("Export chat");
        conversation = conversationRepository.save(conversation);

        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(Message.MessageRole.USER);
        message.setContent(content);
        return messageRepository.save(message);
    }

    @Test
    void exportsTheCallersDocumentsAndConversationsOnly() throws Exception {
        String email = "export-" + UUID.randomUUID() + "@example.com";
        Cookie session = register(email);
        User owner = user(email);
        document(owner, "mine.pdf");
        message(owner, "What does mine.pdf say?");

        // A second account with its own document and message: neither may appear.
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        register(otherEmail);
        User other = user(otherEmail);
        document(other, "theirs.pdf");
        message(other, "Their private question");

        MvcResult result = mockMvc.perform(get("/api/v1/users/me/export").cookie(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.formatVersion").value("1"))
            .andExpect(jsonPath("$.account.email").value(email))
            .andExpect(jsonPath("$.documents.length()").value(1))
            .andExpect(jsonPath("$.documents[0].filename").value("mine.pdf"))
            .andExpect(jsonPath("$.conversations.length()").value(1))
            .andExpect(jsonPath("$.conversations[0].messages.length()").value(1))
            .andExpect(jsonPath("$.conversations[0].messages[0].content")
                .value("What does mine.pdf say?"))
            .andExpect(jsonPath("$.checks.claims").isArray())
            .andExpect(jsonPath("$.checks.claims.length()").value(0))
            .andExpect(jsonPath("$.checks.contradictions").isArray())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("theirs.pdf").doesNotContain("Their private question");

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("exportedAt").asText()).isNotBlank();
    }
}
