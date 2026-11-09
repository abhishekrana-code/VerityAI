package com.verityai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void parsesCommaSeparatedOriginsTrimmingBlanksAndDuplicates() {
        assertThat(CorsConfig.parseOrigins(" https://a.example.com, https://b.example.com ,, https://a.example.com "))
            .containsExactly("https://a.example.com", "https://b.example.com");
    }

    @Test
    void emptyConfigurationMeansNoCrossOriginCallers() {
        assertThat(CorsConfig.parseOrigins(null)).isEmpty();
        assertThat(CorsConfig.parseOrigins("   ")).isEmpty();
    }

    @Test
    void configuredOriginsAreExposedOnTheBean() {
        CorsConfig config = new CorsConfig("https://app.example.com,http://localhost:5173");
        assertThat(config.getAllowedOrigins())
            .containsExactly("https://app.example.com", "http://localhost:5173");
    }
}
