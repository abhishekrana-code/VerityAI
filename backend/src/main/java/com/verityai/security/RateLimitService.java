package com.verityai.security;

import com.verityai.exception.ApiException;
import com.verityai.exception.ErrorCode;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiting backed by resilience4j {@link RateLimiter}s.
 *
 * <p>Limiters are created per key (authenticated user id, or client IP for the
 * pre-auth endpoints) so one caller can never exhaust another caller's quota.
 * A non-positive configured limit disables that check entirely (set to 0 for
 * "unlimited" in the environment).
 *
 * <p>Client IPs must come from {@code request.getRemoteAddr()}: Tomcat's
 * RemoteIpValve is configured to replace it with the forwarded client address
 * only when the request arrives from a trusted proxy.
 *
 * <p>Config keys (application.yml {@code verityai.rate-limit.*}, backed by the
 * {@code RATE_LIMIT_*} environment variables):
 * <ul>
 *   <li>{@code login-per-minute-ip} — login attempts per minute per client IP</li>
 *   <li>{@code register-per-hour-ip} — registrations per hour per client IP</li>
 *   <li>{@code password-reset-per-hour-ip} — reset requests per hour per IP</li>
 *   <li>{@code password-reset-per-hour-account} — reset requests per hour per account</li>
 *   <li>{@code uploads-per-hour} — uploads per hour per user</li>
 *   <li>{@code ai-requests-per-minute} — chat/AI requests per minute per user</li>
 * </ul>
 */
@Service
public class RateLimitService {

    /** Upper bound on tracked keys; idle entries beyond this are swept. */
    private static final int MAX_TRACKED_KEYS = 10_000;
    /** Must exceed the longest refresh period (1h) so live limiters are never swept. */
    private static final Duration MAX_IDLE = Duration.ofHours(2);

    private final RateLimiterConfig loginConfig;
    private final RateLimiterConfig registerConfig;
    private final RateLimiterConfig passwordResetIpConfig;
    private final RateLimiterConfig passwordResetAccountConfig;
    private final RateLimiterConfig uploadConfig;
    private final RateLimiterConfig aiConfig;
    private final Map<String, Entry> limiters = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitService(
            @Value("${verityai.rate-limit.login-per-minute-ip}") int loginPerMinuteIp,
            @Value("${verityai.rate-limit.uploads-per-hour}") int uploadsPerHour,
            @Value("${verityai.rate-limit.ai-requests-per-minute}") int aiRequestsPerMinute,
            @Value("${verityai.rate-limit.register-per-hour-ip:10}") int registerPerHourIp,
            @Value("${verityai.rate-limit.password-reset-per-hour-ip:5}") int passwordResetPerHourIp,
            @Value("${verityai.rate-limit.password-reset-per-hour-account:3}") int passwordResetPerHourAccount) {
        this.loginConfig = config(loginPerMinuteIp, Duration.ofMinutes(1));
        this.registerConfig = config(registerPerHourIp, Duration.ofHours(1));
        this.passwordResetIpConfig = config(passwordResetPerHourIp, Duration.ofHours(1));
        this.passwordResetAccountConfig = config(passwordResetPerHourAccount, Duration.ofHours(1));
        this.uploadConfig = config(uploadsPerHour, Duration.ofHours(1));
        this.aiConfig = config(aiRequestsPerMinute, Duration.ofMinutes(1));
    }

    /** Convenience constructor for focused unit tests (registration/reset disabled). */
    public RateLimitService(int loginPerMinuteIp, int uploadsPerHour, int aiRequestsPerMinute) {
        this(loginPerMinuteIp, uploadsPerHour, aiRequestsPerMinute, 0, 0, 0);
    }

    /** Consumes one login attempt for this client IP; 429 when exhausted. */
    public void checkLogin(String clientIp) {
        acquire("login", clientIp, loginConfig, ErrorCode.RATE_LIMITED,
                "Too many login attempts, please try again later", 60);
    }

    /**
     * Consumes one login attempt for the targeted account (any IP); 429 when
     * exhausted. Complements the IP limit: it stops credential stuffing of one
     * account across many IPs without letting one attacker exhaust others.
     */
    public void checkLoginAccount(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        acquire("loginacct", email.trim().toLowerCase(Locale.ROOT), loginConfig,
                ErrorCode.RATE_LIMITED, "Too many login attempts, please try again later", 60);
    }

    /** Consumes one registration slot for this client IP; 429 when exhausted. */
    public void checkRegister(String clientIp) {
        acquire("register", clientIp, registerConfig, ErrorCode.RATE_LIMITED,
                "Too many registration attempts, please try again later", 3600);
    }

    /** Consumes one password-reset request slot for this client IP. */
    public void checkPasswordReset(String clientIp) {
        acquire("resetip", clientIp, passwordResetIpConfig, ErrorCode.RATE_LIMITED,
                "Too many password reset requests, please try again later", 3600);
    }

    /** Consumes one password-reset request slot for the target account. */
    public void checkPasswordResetAccount(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        acquire("resetacct", email.trim().toLowerCase(Locale.ROOT), passwordResetAccountConfig,
                ErrorCode.RATE_LIMITED, "Too many password reset requests, please try again later", 3600);
    }

    /** Consumes one upload slot for this user; 429 when exhausted. */
    public void checkUpload(UUID userId) {
        acquire("upload", userId.toString(), uploadConfig, ErrorCode.RATE_LIMITED,
                "Upload limit reached, please try again later", 3600);
    }

    /** Consumes one AI/chat request slot for this user; 429 when exhausted. */
    public void checkAiRequest(UUID userId) {
        acquire("ai", userId.toString(), aiConfig, ErrorCode.AI_RATE_LIMITED,
                "AI request limit reached, please try again later", 60);
    }

    private void acquire(String bucket, String key, RateLimiterConfig config,
                         ErrorCode errorCode, String message, long retryAfterSeconds) {
        if (config == null) {
            return; // limit disabled by non-positive configuration
        }
        String id = bucket + ":" + key;
        long now = System.nanoTime();
        Entry entry = limiters.computeIfAbsent(id, k -> new Entry(RateLimiter.of(k, config)));
        entry.lastAccess = now;
        if (!entry.limiter.acquirePermission()) {
            throw new ApiException(errorCode, message, 429, null, retryAfterSeconds);
        }
        if (limiters.size() > MAX_TRACKED_KEYS) {
            sweep(now);
        }
    }

    /** Bounds memory when the pre-auth limiters see many distinct client IPs. */
    private void sweep(long now) {
        long maxIdle = MAX_IDLE.toNanos();
        limiters.entrySet().removeIf(e -> now - e.getValue().lastAccess > maxIdle);
    }

    private static RateLimiterConfig config(int limit, Duration refreshPeriod) {
        if (limit <= 0) {
            return null; // 0/negative disables this limit
        }
        return RateLimiterConfig.custom()
                .limitForPeriod(limit)
                .limitRefreshPeriod(refreshPeriod)
                .timeoutDuration(Duration.ZERO) // fail fast with 429 instead of queueing
                .build();
    }

    private static final class Entry {
        final RateLimiter limiter;
        volatile long lastAccess = System.nanoTime();

        Entry(RateLimiter limiter) {
            this.limiter = limiter;
        }
    }
}
