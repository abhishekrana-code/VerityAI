package com.verityai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack auth contract test: register/login/me, session cookie flow,
 * 401 for protected endpoints and the CSRF cookie-to-header contract the
 * frontend api.ts relies on (XSRF-TOKEN cookie -> X-XSRF-TOKEN header).
 * Runs against a real PostgreSQL container with Flyway migrations applied.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String registerBody(String email) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", PASSWORD,
                "displayName", "Test User"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Registers a fresh user and returns the session cookie established by
     * the response (Spring Session stores auth state server-side, keyed by
     * this cookie - mirroring how a browser carries the session).
     */
    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.user.email").value(email))
            .andReturn();
        Cookie sessionCookie = result.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).as("SESSION cookie after register").isNotNull();
        return sessionCookie;
    }

    @Test
    void registerEstablishesSessionThatServesMeEndpoint() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email).replace(PASSWORD, "wrong-password")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void loginWithCorrectPasswordReturnsSession() throws Exception {
        String email = uniqueEmail();
        register(email);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andReturn();

        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie after login").isNotNull();

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void meWithoutSessionReportsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void protectedEndpointWithoutSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/conversations"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void mutatingRequestWithoutCsrfTokenReturnsForbidden() throws Exception {
        Cookie session = register(uniqueEmail());

        mockMvc.perform(post("/api/v1/conversations")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"no csrf\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void csrfTokenFromEndpointEnablesMutatingRequest() throws Exception {
        Cookie session = register(uniqueEmail());

        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn();
        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).as("XSRF-TOKEN cookie after /auth/csrf").isNotNull();
        String token = objectMapper
            .readTree(csrfResult.getResponse().getContentAsString())
            .get("token").asText();

        mockMvc.perform(post("/api/v1/conversations")
                .cookie(session, xsrfCookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"with csrf\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.title").value("with csrf"));
    }

    @Test
    void logoutReturnsOkAndClearsSessionCookie() throws Exception {
        Cookie session = register(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
            .andExpect(status().isOk())
            .andExpect(result -> {
                MockHttpServletResponse response = result.getResponse();
                Cookie cleared = response.getCookie("SESSION");
                assertThat(cleared).as("SESSION deletion cookie after logout").isNotNull();
                assertThat(cleared.getMaxAge()).isZero();
            });
    }
}
