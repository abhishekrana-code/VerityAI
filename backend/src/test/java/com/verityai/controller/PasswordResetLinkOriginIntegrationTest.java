package com.verityai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.User;
import com.verityai.repository.PasswordResetTokenRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.mail.MailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When {@code APP_BASE_URL} is not pinned, the reset link must follow the
 * browser's own origin so it stays reachable (localhost vs 127.0.0.1 vs a LAN
 * address), and must refuse an origin outside the CORS allowlist so the email
 * can never be pointed at an attacker-controlled host.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.register-per-hour-ip=100",
    "verityai.rate-limit.password-reset-per-hour-ip=100",
    "verityai.rate-limit.password-reset-per-hour-account=100",
    // Deliberately unset: the link must be derived from the request.
    "verityai.app.base-url=",
    "verityai.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class PasswordResetLinkOriginIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository tokenRepository;

    @MockitoBean private MailService mailService;

    private String uniqueEmail() {
        return "reset-origin-" + UUID.randomUUID() + "@example.com";
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Origin Test"))))
            .andExpect(status().isOk());
    }

    private void requestReset(String email, String origin) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))))
            .andExpect(status().isOk())
            .andExpect(content().string(""));
    }

    private long tokensFor(String email) {
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        return tokenRepository.findAll().stream()
            .filter(token -> token.getUserId().equals(user.getId()))
            .count();
    }

    @Test
    void linkUsesTheRequestingBrowsersOrigin() throws Exception {
        String email = uniqueEmail();
        register(email);

        requestReset(email, "http://127.0.0.1:5173");

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000)).sendPasswordReset(eq(email), link.capture());
        assertThat(link.getValue())
            .startsWith("http://127.0.0.1:5173/reset-password?token=");
    }

    @Test
    void originOutsideTheAllowlistIsRejectedBeforeAnyTokenIsCreated() throws Exception {
        String email = uniqueEmail();
        register(email);

        // The CORS filter rejects an untrusted Origin outright, so a forged
        // request never reaches the controller (and cannot mint a token).
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                .header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))))
            .andExpect(status().isForbidden());

        verify(mailService, timeout(1500).times(0))
            .sendPasswordReset(eq(email), anyString());
        assertThat(tokensFor(email)).as("tokens created for a rejected origin").isZero();
    }
}
