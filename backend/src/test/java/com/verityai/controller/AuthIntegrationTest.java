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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    "verityai.rate-limit.register-per-hour-ip=1000",
    "verityai.rate-limit.password-reset-per-hour-ip=1000",
    "verityai.rate-limit.password-reset-per-hour-account=1000",
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
     * Registers a fresh user (no session is created) and then logs in,
     * returning the session cookie established by the login response.
     */
    private Cookie register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
        return login(email);
    }

    private Cookie login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andReturn();
        Cookie sessionCookie = result.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).as("SESSION cookie after login").isNotNull();
        return sessionCookie;
    }

    /** Keeps the shared per-IP login quota isolated between test methods. */
    private static String uniqueIp() {
        java.util.concurrent.ThreadLocalRandom random =
            java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255)
            + "." + random.nextInt(1, 255);
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
    void duplicateRegistrationIsIndistinguishableFromNewRegistration() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.user").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void registrationDoesNotCreateAnAuthenticatedSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(uniqueEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false))
            .andReturn();

        assertThat(result.getResponse().getCookie("SESSION"))
            .as("registration must not establish a session")
            .isNull();
    }

    @Test
    void loginRotatesTheSessionId() throws Exception {
        String attackerEmail = uniqueEmail();
        String victimEmail = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(attackerEmail)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(victimEmail)))
            .andExpect(status().isOk());

        // Attacker has a real authenticated session they plant in the victim's
        // browser, then the victim logs in with that cookie.
        Cookie planted = login(attackerEmail);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .cookie(planted)
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(victimEmail)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie rotated = loginResult.getResponse().getCookie("SESSION");
        assertThat(rotated).as("SESSION cookie after login").isNotNull();
        assertThat(rotated.getValue())
            .as("session id must change on authentication")
            .isNotEqualTo(planted.getValue());

        // The planted cookie is dead (neither the attacker's nor the victim's
        // identity remains attached to it).
        mockMvc.perform(get("/api/v1/conversations").cookie(planted))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(rotated))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.user.email").value(victimEmail));
    }

    @Test
    void secondLoginInvalidatesTheFirstSession() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk());

        MvcResult first = mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andReturn();
        Cookie firstSession = first.getResponse().getCookie("SESSION");
        assertThat(firstSession).isNotNull();

        MvcResult second = mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email)))
            .andExpect(status().isOk())
            .andReturn();
        Cookie secondSession = second.getResponse().getCookie("SESSION");
        assertThat(secondSession).isNotNull();

        // Old device is signed out (JSON 401 on a protected endpoint) ...
        mockMvc.perform(get("/api/v1/conversations").cookie(firstSession))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // ... the new device remains authenticated.
        mockMvc.perform(get("/api/v1/auth/me").cookie(secondSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.user.email").value(email));
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

    @Test
    void profileUpdateIsVisibleImmediatelyAndSurvivesPasswordChange() throws Exception {
        String email = uniqueEmail();
        Cookie session = register(email);

        mockMvc.perform(patch("/api/v1/auth/users/me")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Updated Name\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Updated Name"));

        // The session principal is a snapshot; /auth/me must reflect the update
        // without requiring a new login.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.displayName").value("Updated Name"));

        // Changing the password must not merge a stale principal back over the
        // profile fields that were just updated.
        mockMvc.perform(post("/api/v1/auth/users/me/password")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD
                    + "\",\"newPassword\":\"new-password-123\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.displayName").value("Updated Name"));

        MvcResult relogin = mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"new-password-123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.displayName").value("Updated Name"))
            .andReturn();
        assertThat(relogin.getResponse().getCookie("SESSION")).isNotNull();
    }
}
