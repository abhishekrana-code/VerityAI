package com.verityai.service.auth;

import com.verityai.entity.PasswordResetToken;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.PasswordResetTokenRepository;
import com.verityai.repository.UserRepository;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
    private final long tokenTtlMinutes;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            @Qualifier("mailTaskExecutor") ThreadPoolTaskExecutor mailExecutor,
            @Value("${verityai.app.base-url:http://localhost:5173}") String appBaseUrl,
            @Value("${verityai.password-reset.token-ttl-minutes:30}") long tokenTtlMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.sessionRepository = sessionRepository;
        this.mailExecutor = mailExecutor;
        this.appBaseUrl = appBaseUrl.endsWith("/")
            ? appBaseUrl.substring(0, appBaseUrl.length() - 1)
            : appBaseUrl;
        this.tokenTtlMinutes = tokenTtlMinutes > 0 ? tokenTtlMinutes : 30;
    }

    /**
     * Creates and mails a token when the account exists. Unknown emails are
     * silently ignored — the controller returns the same 200 either way.
     */
    @Transactional
    public void request(String email) {
        // Do the random-token work unconditionally so the unknown-email path is
        // not measurably cheaper than the known-email path.
        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);

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

                String link = appBaseUrl + "/reset-password?token=" + rawToken;
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
