package com.verityai.security;

import com.verityai.exception.ApiException;
import com.verityai.exception.ErrorCode;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiting backed by resilience4j {@link RateLimiter}s.
 *
 * <p>Limiters are created per key (authenticated user id, or client IP for the
 * pre-auth login endpoint) so one caller can never exhaust another caller's
 * quota. A non-positive configured limit disables that check entirely
 * (set to 0 for "unlimited" in the environment).
 *
 * <p>Config keys (application.yml {@code verityai.rate-limit.*}, backed by the
 * {@code RATE_LIMIT_*} environment variables):
 * <ul>
 *   <li>{@code login} — attempts per minute per client IP</li>
 *   <li>{@code upload} — uploads per hour per user</li>
 *   <li>{@code ai} — chat/AI requests per minute per user</li>
 * </ul>
 */
@Service
public class RateLimitService {

    /** Upper bound on tracked keys; idle entries beyond this are swept. */
    private static final int MAX_TRACKED_KEYS = 10_000;
    /** Must exceed the longest refresh period (1h) so live limiters are never swept. */
    private static final Duration MAX_IDLE = Duration.ofHours(2);

    private final RateLimiterConfig loginConfig;
    private final RateLimiterConfig uploadConfig;
    private final RateLimiterConfig aiConfig;
    private final Map<String, Entry> limiters = new ConcurrentHashMap<>();

    public RateLimitService(
            @Value("${verityai.rate-limit.login-per-minute-ip}") int loginPerMinuteIp,
            @Value("${verityai.rate-limit.uploads-per-hour}") int uploadsPerHour,
            @Value("${verityai.rate-limit.ai-requests-per-minute}") int aiRequestsPerMinute) {
        this.loginConfig = config(loginPerMinuteIp, Duration.ofMinutes(1));
        this.uploadConfig = config(uploadsPerHour, Duration.ofHours(1));
        this.aiConfig = config(aiRequestsPerMinute, Duration.ofMinutes(1));
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
        acquire("loginacct", email.trim().toLowerCase(java.util.Locale.ROOT), loginConfig,
                ErrorCode.RATE_LIMITED, "Too many login attempts, please try again later", 60);
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

    /** Bounds memory when the login limiter sees many distinct client IPs. */
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
