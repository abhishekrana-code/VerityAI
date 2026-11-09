package com.verityai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.PasswordResetToken;
import com.verityai.repository.PasswordResetTokenRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.mail.MailService;
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
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end password reset contract against a real database. Mail is mocked:
 * the test captures the link, extracts the token, and exercises single-use,
 * expiry, password change and session invalidation.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.login-per-minute-ip=100",
    "verityai.rate-limit.register-per-hour-ip=100",
    "verityai.rate-limit.password-reset-per-hour-ip=100",
    "verityai.rate-limit.password-reset-per-hour-account=100",
    "verityai.app.base-url=http://localhost:5173",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class PasswordResetIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";
    private static final String NEW_PASSWORD = "new-password-456";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private MailService mailService;

    private String uniqueEmail() {
        return "reset-" + UUID.randomUUID() + "@example.com";
    }

    private String body(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "email", email, "password", password, "displayName", "Reset Test"));
    }

    private void registerOnly(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    private Cookie login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, password)))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie after login").isNotNull();
        return session;
    }

    private void requestReset(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))))
            .andExpect(status().isOk())
            .andExpect(content().string(""));
    }

    private void confirm(String token, String newPassword) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "token", token, "newPassword", newPassword))))
            .andExpect(status().isOk());
    }

    private String captureToken(String email) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000)).sendPasswordReset(eq(email), link.capture());
        String token = UriComponentsBuilder.fromUriString(link.getValue()).build()
            .getQueryParams().getFirst("token");
        assertThat(token).as("token in reset link").isNotBlank();
        return token;
    }

    @Test
    void requestIsGenericForKnownAndUnknownEmails() throws Exception {
        String known = uniqueEmail();
        String unknown = "missing-" + UUID.randomUUID() + "@example.com";
        registerOnly(known);

        requestReset(unknown);
        requestReset(known);

        verify(mailService, timeout(5000)).sendPasswordReset(eq(known), contains("token="));
        verify(mailService, never()).sendPasswordReset(eq(unknown), anyString());
    }

    @Test
    void confirmChangesPasswordAndInvalidatesSessions() throws Exception {
        String email = uniqueEmail();
        registerOnly(email);
        Cookie session = login(email, PASSWORD);

        requestReset(email);
        String token = captureToken(email);
        confirm(token, NEW_PASSWORD);

        // Every existing session is invalidated.
        mockMvc.perform(get("/api/v1/conversations").cookie(session))
            .andExpect(status().isUnauthorized());

        // Old password no longer works; new one does.
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, PASSWORD)))
            .andExpect(status().isUnauthorized());

        Cookie newSession = login(email, NEW_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").cookie(newSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String email = uniqueEmail();
        registerOnly(email);
        requestReset(email);
        String token = captureToken(email);

        confirm(token, NEW_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "token", token, "newPassword", "another-password-789"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "token", "not-a-real-token", "newPassword", NEW_PASSWORD))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        registerOnly(email);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();

        String rawToken = "expired-token-" + UUID.randomUUID();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(userId);
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        tokenRepository.save(token);

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "token", rawToken, "newPassword", NEW_PASSWORD))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
