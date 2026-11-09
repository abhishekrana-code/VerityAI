package com.verityai.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS must allow the configured frontend origin (with credentials) and reject
 * any other origin. The allowed origin is supplied the same way production
 * does: through {@code CORS_ALLOWED_ORIGINS} / the matching property.
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.rate-limit.register-per-hour-ip=1000",
    "verityai.cors.allowed-origins=https://app.example.com",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class CorsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    @Autowired private MockMvc mockMvc;

    @Test
    void configuredOriginIsAllowedWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                .header("Origin", "https://app.example.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void unlistedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden());
    }
}
