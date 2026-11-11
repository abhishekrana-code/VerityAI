package com.verityai.service.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reset link must point at a host the user can actually reach, without
 * letting a forged request header redirect the email to an attacker.
 */
class PasswordResetLinkResolutionTest {

    private static final List<String> ALLOWED =
        List.of("http://localhost:5173", "http://127.0.0.1:5173");

    @Test
    void explicitConfigurationAlwaysWins() {
        assertThat(PasswordResetService.resolveBaseUrl(
            "https://app.verityai.example", ALLOWED, "http://localhost:5173", null))
            .isEqualTo("https://app.verityai.example");
    }

    @Test
    void trailingSlashInConfigurationIsRemoved() {
        assertThat(PasswordResetService.resolveBaseUrl(
            "https://app.verityai.example/", ALLOWED, null, null))
            .isEqualTo("https://app.verityai.example");
    }

    @Test
    void allowedBrowserOriginIsUsedWhenNothingIsConfigured() {
        assertThat(PasswordResetService.resolveBaseUrl(
            "", ALLOWED, "http://127.0.0.1:5173", null))
            .isEqualTo("http://127.0.0.1:5173");
    }

    @Test
    void originOutsideTheAllowlistIsRejected() {
        // Classic reset-link poisoning: the attacker triggers a reset for the
        // victim and supplies their own Origin. No link must be produced.
        assertThat(PasswordResetService.resolveBaseUrl(
            "", ALLOWED, "https://evil.example", null))
            .isNull();
    }

    @Test
    void refererIsUsedOnlyWhenOriginIsMissing() {
        assertThat(PasswordResetService.resolveBaseUrl(
            "", ALLOWED, null, "http://localhost:5173/reset-password?token=abc"))
            .isEqualTo("http://localhost:5173");

        // A forged Origin must not be rescued by a legitimate Referer.
        assertThat(PasswordResetService.resolveBaseUrl(
            "", ALLOWED, "https://evil.example", "http://localhost:5173/forgot-password"))
            .isNull();
    }

    @Test
    void nonHttpSchemesAreRejected() {
        assertThat(PasswordResetService.resolveBaseUrl("", ALLOWED, "javascript:alert(1)", null))
            .isNull();
        assertThat(PasswordResetService.resolveBaseUrl("", ALLOWED, "ftp://localhost:5173", null))
            .isNull();
        assertThat(PasswordResetService.resolveBaseUrl("", List.of("javascript:alert(1)"),
            "javascript:alert(1)", null))
            .isNull();
    }

    @Test
    void noConfigurationAndNoHeadersYieldsNoLink() {
        assertThat(PasswordResetService.resolveBaseUrl("", ALLOWED, null, null)).isNull();
        assertThat(PasswordResetService.resolveBaseUrl("   ", ALLOWED, "", "  ")).isNull();
    }

    @Test
    void allowlistEntriesAreNormalisedBeforeComparison() {
        assertThat(PasswordResetService.resolveBaseUrl(
            "", List.of("http://localhost:5173/"), "http://localhost:5173", null))
            .isEqualTo("http://localhost:5173");
    }

    @Test
    void defaultPortsAreNotDuplicatedInTheLink() {
        assertThat(PasswordResetService.resolveBaseUrl(
            "", List.of("https://app.example.com"), "https://app.example.com:443", null))
            .isEqualTo("https://app.example.com");
        assertThat(PasswordResetService.resolveBaseUrl(
            "", List.of("https://app.example.com:8443"), "https://app.example.com:8443", null))
            .isEqualTo("https://app.example.com:8443");
    }
}
