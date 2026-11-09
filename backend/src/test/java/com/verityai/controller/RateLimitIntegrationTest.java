package com.verityai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting contract tests against the real endpoints. Limits are pinned
 * low via {@code @SpringBootTest} properties, which yields a dedicated Spring
 * context (and therefore fresh limiter state) separate from AuthIntegrationTest.
 *
 * <p>Quota isolation between test methods: the login quota is per client IP
 * (MockMvc reports 127.0.0.1 unless a test overrides it). The {@code register}
 * helper logs in from a random IP so helpers do not consume each other's
 * quota; {@code thirdLoginFromSameIpReturns429} pins one IP for its three
 * attempts. Upload and AI quotas are per user, and each test registers its own
 * user.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.register-per-hour-ip=1000",
    "verityai.rate-limit.login-per-minute-ip=2",
    "verityai.rate-limit.uploads-per-hour=1",
    "verityai.rate-limit.ai-requests-per-minute=1",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class RateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private record Csrf(Cookie cookie, String token) {}

    private String uniqueEmail() {
        return "rl-" + UUID.randomUUID() + "@example.com";
    }

    private String registerBody(String email) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "email", email,
            "password", PASSWORD,
            "displayName", "Rate Limit Test"));
    }

    /** Registers a fresh user and returns the session cookie established by login. */
    private Cookie register(String email) throws Exception {
        registerOnly(email);
        return login(email, uniqueIp());
    }

    /** Creates the account without a session (registration is intentionally session-free). */
    private void registerOnly(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk());
    }

    private Cookie login(String email, String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie after login").isNotNull();
        return session;
    }

    /** Keeps the shared per-IP login quota isolated between test methods. */
    private static String uniqueIp() {
        java.util.concurrent.ThreadLocalRandom random =
            java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255)
            + "." + random.nextInt(1, 255);
    }

    /** Provisions the XSRF-TOKEN cookie + token exactly like the browser flow. */
    private Csrf csrf(Cookie session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("XSRF-TOKEN cookie after /auth/csrf").isNotNull();
        String token = objectMapper
            .readTree(result.getResponse().getContentAsString())
            .get("token").asText();
        return new Csrf(xsrf, token);
    }

    @Test
    void thirdLoginFromSameIpReturns429() throws Exception {
        String email = uniqueEmail();
        registerOnly(email); // account creation does not consume login quota

        mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.10");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.10");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.10");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void secondAskReturns429AiRateLimited() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String url = "/api/v1/conversations/" + UUID.randomUUID() + "/messages";
        String body = objectMapper.writeValueAsString(Map.of("question", "What is the revenue growth?"));

        // First ask consumes the quota; the conversation does not exist -> 404
        // (the rate check runs first, and no LLM call is ever made).
        mockMvc.perform(post(url)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());

        mockMvc.perform(post(url)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"));
    }

    @Test
    void secondUploadReturns429() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);

        MockMultipartFile first = new MockMultipartFile(
            "file", "first.pdf", "application/pdf",
            "%PDF-1.4 first upload".getBytes(StandardCharsets.US_ASCII));
        mockMvc.perform(multipart("/api/v1/documents")
                .file(first)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk());

        MockMultipartFile second = new MockMultipartFile(
            "file", "second.pdf", "application/pdf",
            "%PDF-1.4 second upload".getBytes(StandardCharsets.US_ASCII));
        mockMvc.perform(multipart("/api/v1/documents")
                .file(second)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "3600"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void uploadWithPdfExtensionButNoMagicBytesIsRejectedBeforeStorage() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);

        MockMultipartFile fake = new MockMultipartFile(
            "file", "fake.pdf", "application/pdf",
            "this is definitely not a pdf".getBytes(StandardCharsets.US_ASCII));
        mockMvc.perform(multipart("/api/v1/documents")
                .file(fake)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("%PDF-")));
    }
}
