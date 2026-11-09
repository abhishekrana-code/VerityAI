package com.verityai.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the real Tomcat valve honours {@code X-Forwarded-For} from a
 * trusted peer (the test client connects over loopback, which is the only
 * trusted proxy by default). Two different accounts from the same forwarded IP
 * share one login bucket; a different forwarded IP gets its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.login-per-minute-ip=1",
    "verityai.rate-limit.register-per-hour-ip=100",
    "verityai.rate-limit.password-reset-per-hour-ip=100",
    "verityai.rate-limit.password-reset-per-hour-account=100",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@Testcontainers
class ForwardedHeaderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    @Autowired private TestRestTemplate rest;

    private ResponseEntity<String> login(String email, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        String body = "{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}";
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void rateLimiterUsesTheForwardedClientIp() {
        // Bad credentials: the limiter runs before authentication, so the
        // first request is a 401 that consumes the forwarded IP's only slot.
        assertThat(login("fwd-a@example.com", "203.0.113.7").getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A different account from the same forwarded IP is throttled.
        ResponseEntity<String> throttled = login("fwd-b@example.com", "203.0.113.7");
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst("Retry-After")).isEqualTo("60");

        // A different forwarded IP is unaffected.
        assertThat(login("fwd-c@example.com", "203.0.113.8").getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
