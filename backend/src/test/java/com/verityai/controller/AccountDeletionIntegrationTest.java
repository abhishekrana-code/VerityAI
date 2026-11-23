package com.verityai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.Tag;
import com.verityai.entity.User;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.repository.TagRepository;
import com.verityai.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account deletion contract: the password is required again, and afterwards no
 * row, file or session of the user survives.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.register-per-hour-ip=1000",
    "verityai.rate-limit.login-per-minute-ip=1000",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountDeletionIntegrationTest {

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
    @Autowired private TagRepository tagRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", PASSWORD,
                    "displayName", "Delete Me"))))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        return session;
    }

    private record Csrf(String token, Cookie cookie) {
    }

    /** The app's CSRF contract: XSRF-TOKEN cookie + X-XSRF-TOKEN header. */
    private Csrf csrf(String sessionValue) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")
                .cookie(new Cookie("SESSION", sessionValue)))
            .andExpect(status().isOk())
            .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN cookie").isNotNull();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("token").asText();
        return new Csrf(token, cookie);
    }

    private Document document(User owner) {
        Document document = new Document();
        document.setUser(owner);
        document.setOriginalFilename("report.pdf");
        document.setStoredFilename("delete-me-" + UUID.randomUUID() + ".pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(2048L);
        document.setSha256((UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "")).substring(0, 64));
        document.setStatus(Document.DocumentStatus.READY);
        return documentRepository.save(document);
    }

    private Map<String, String> passwordBody(String password) {
        return Map.of("password", password);
    }

    @Test
    void requiresThePasswordAndLeavesNothingBehind() throws Exception {
        String email = "delete-" + UUID.randomUUID() + "@example.com";
        Cookie session = register(email);
        Csrf csrf = csrf(session.getValue());
        User owner = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        Document document = document(owner);
        Path storedFile = Paths.get("target/test-uploads").toAbsolutePath()
            .resolve(document.getStoredFilename());
        Files.createDirectories(storedFile.getParent());
        Files.writeString(storedFile, "%PDF-1.4");
        Conversation conversation = new Conversation();
        conversation.setUser(owner);
        conversation.setTitle("Delete me");
        conversation = conversationRepository.save(conversation);
        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(Message.MessageRole.USER);
        message.setContent("What is this report about?");
        message = messageRepository.save(message);
        Tag tag = new Tag();
        tag.setUser(owner);
        tag.setName("private-" + UUID.randomUUID());
        tag = tagRepository.save(tag);
        UUID userId = owner.getId();

        // A stolen session is not enough.
        mockMvc.perform(delete("/api/v1/users/me")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(passwordBody("wrong-password"))))
            .andExpect(status().isForbidden());
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(Files.exists(storedFile)).isTrue();

        mockMvc.perform(delete("/api/v1/users/me")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(passwordBody(PASSWORD))))
            .andExpect(status().isNoContent());

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(documentRepository.findById(document.getId())).isEmpty();
        assertThat(conversationRepository.findById(conversation.getId())).isEmpty();
        assertThat(messageRepository.findById(message.getId())).isEmpty();
        assertThat(tagRepository.findById(tag.getId())).isEmpty();
        assertThat(Files.exists(storedFile)).isFalse();

        MockHttpServletResponse me = mockMvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        JsonNode body = objectMapper.readTree(me.getContentAsString());
        assertThat(body.get("authenticated").asBoolean()).isFalse();

        // The email is free again and the old password no longer signs in.
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD))))
            .andExpect(status().isUnauthorized());
    }

    /**
     * The race the cascade closes: deletion locks the user row, and an upload's
     * foreign-key check needs a lock on that same row, so an upload can never
     * commit a document between the delete's read and its write.
     */
    @Test
    void deletionWaitsForTheUserRowLockSoAnUploadCannotLandMidDelete() throws Exception {
        String email = "locked-" + UUID.randomUUID() + "@example.com";
        Cookie session = register(email);
        Csrf csrf = csrf(session.getValue());
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> {
                userRepository.findByIdForUpdate(userId).orElseThrow();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("holder acquired the row lock").isTrue();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> deletion = pool.submit(() -> mockMvc.perform(delete("/api/v1/users/me")
                    .cookie(session, csrf.cookie())
                    .header("X-XSRF-TOKEN", csrf.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(passwordBody(PASSWORD))))
                .andReturn().getResponse().getStatus());

            assertThat(awaitBlockedDelete(deletion))
                .as("deletion must block on the user row lock instead of deleting under a live upload")
                .isTrue();
            // Guards against matching another session's wait: our request is
            // not finished while it is blocked on the lock.
            assertThat(deletion.isDone()).isFalse();

            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            release.countDown();
            pool.shutdownNow();
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    /**
     * True once Postgres reports another active session waiting for a lock while
     * running a statement on {@code users} — the blocked deletion. Scoped to
     * other sessions, so the polling query itself can never match.
     */
    private boolean awaitBlockedDelete(Future<?> deletion) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !deletion.isDone()) {
            Integer waiting = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND state = 'active'
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%from users%'
                """, Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
