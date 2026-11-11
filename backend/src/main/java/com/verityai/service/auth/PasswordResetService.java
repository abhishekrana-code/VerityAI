package com.verityai.service.auth;

import com.verityai.entity.PasswordResetToken;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.PasswordResetTokenRepository;
import com.verityai.repository.UserRepository;
import com.verityai.config.CorsConfig;
import com.verityai.service.mail.MailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Password-reset flow.
 *
 * <p>Request is deliberately non-enumerating: it always does the same work and
 * always succeeds from the caller's point of view. Tokens are 256-bit random
 * values; only their SHA-256 hash is stored. Confirmation is single-use and
 * also invalidates every active session for the account.
 */
@Service
@Slf4j
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final ThreadPoolTaskExecutor mailExecutor;
    private final String appBaseUrl;
    private final List<String> allowedOrigins;
    private final long tokenTtlMinutes;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            @Qualifier("mailTaskExecutor") ThreadPoolTaskExecutor mailExecutor,
            @Value("${verityai.app.base-url:}") String appBaseUrl,
            @Value("${verityai.cors.allowed-origins:}") String allowedOrigins,
            @Value("${verityai.password-reset.token-ttl-minutes:30}") long tokenTtlMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.sessionRepository = sessionRepository;
        this.mailExecutor = mailExecutor;
        this.appBaseUrl = trimTrailingSlash(appBaseUrl);
        this.allowedOrigins = CorsConfig.parseOrigins(allowedOrigins);
        this.tokenTtlMinutes = tokenTtlMinutes > 0 ? tokenTtlMinutes : 30;
    }

    /**
     * Creates and mails a token when the account exists. Unknown emails are
     * silently ignored — the controller returns the same 200 either way.
     *
     * @param originHeader  browser {@code Origin}; used only when
     *                      {@code APP_BASE_URL} is not configured
     * @param refererHeader browser {@code Referer}; fallback when Origin is absent
     */
    @Transactional
    public void request(String email, String originHeader, String refererHeader) {
        // Do the random-token work unconditionally so the unknown-email path is
        // not measurably cheaper than the known-email path.
        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);

        String baseUrl = resolveBaseUrl(appBaseUrl, allowedOrigins, originHeader, refererHeader);
        if (baseUrl == null) {
            // Mailing a link to an origin we do not trust would be worse than
            // sending nothing: it is exactly how reset-link poisoning works.
            log.error("Cannot build a password-reset link: set APP_BASE_URL to the public "
                + "frontend URL, or add the frontend origin to CORS_ALLOWED_ORIGINS");
            return;
        }

        String normalized = email.trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmailIgnoreCase(normalized)
            .filter(user -> user.getDeletedAt() == null)
            .ifPresent(user -> {
                tokenRepository.deleteByUserId(user.getId());

                PasswordResetToken token = new PasswordResetToken();
                token.setUserId(user.getId());
                token.setTokenHash(tokenHash);
                token.setExpiresAt(Instant.now().plus(tokenTtlMinutes, ChronoUnit.MINUTES));
                tokenRepository.save(token);

                String link = baseUrl + "/reset-password?token=" + rawToken;
                sendAfterCommit(user.getEmail(), link);
            });
    }

    @Transactional
    public void confirm(String rawToken, String newPassword) {
        String tokenHash = sha256Hex(rawToken);
        PasswordResetToken token = tokenRepository.findByTokenHashAndUsedAtIsNull(tokenHash)
            .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> ApiException.validation("Invalid or expired password reset token"));

        User user = userRepository.findById(token.getUserId())
            .filter(candidate -> candidate.getDeletedAt() == null)
            .orElseThrow(() -> ApiException.validation("Invalid or expired password reset token"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
        // Single-use: remove every outstanding token for this account.
        tokenRepository.deleteByUserId(user.getId());

        invalidateSessions(user.getEmail());
        log.info("Password reset completed for user {}", user.getId());
    }

    private void sendAfterCommit(String to, String link) {
        Runnable send = () -> mailService.sendPasswordReset(to, link);
        Runnable submit = () -> {
            try {
                mailExecutor.execute(send);
            } catch (TaskRejectedException e) {
                log.error("Mail queue full; password reset email to {} was not queued", to);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private void invalidateSessions(String email) {
        try {
            sessionRepository.findByPrincipalName(email).keySet()
                .forEach(sessionRepository::deleteById);
        } catch (Exception e) {
            log.warn("Could not invalidate sessions after password reset for {}", email, e);
        }
    }

    /**
     * Picks the base URL used to build the reset link.
     *
     * <p>An explicit {@code APP_BASE_URL} always wins: production pins the
     * public origin so the link is stable regardless of how the API is reached.
     * When it is unset, the browser's own origin is used — but only if it is in
     * the CORS allowlist. Deriving it from the request keeps local and LAN
     * development links reachable (the emailed host is the host the user is
     * actually browsing), while the allowlist check stops a forged Origin from
     * pointing a victim's reset email at an attacker-controlled host.
     *
     * @return a normalised {@code scheme://host[:port]} or {@code null} when no
     *         trustworthy origin is available
     */
    static String resolveBaseUrl(String configuredBaseUrl,
                                 List<String> allowedOrigins,
                                 String originHeader,
                                 String refererHeader) {
        if (StringUtils.hasText(configuredBaseUrl)) {
            return trimTrailingSlash(configuredBaseUrl.trim());
        }

        String candidate = originOf(originHeader);
        if (candidate == null) {
            candidate = originOf(refererHeader);
        }
        if (candidate == null) {
            return null;
        }

        boolean allowed = allowedOrigins.stream()
            .map(PasswordResetService::originOf)
            .anyMatch(candidate::equals);
        return allowed ? candidate : null;
    }

    /** Returns {@code scheme://host[:port]} for an absolute http(s) URL, else null. */
    private static String originOf(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        try {
            URI uri = URI.create(headerValue.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
                return null;
            }
            String origin = normalizedScheme + "://" + uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            int defaultPort = normalizedScheme.equals("https") ? 443 : 80;
            if (port != -1 && port != defaultPort) {
                origin += ":" + port;
            }
            return origin;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
