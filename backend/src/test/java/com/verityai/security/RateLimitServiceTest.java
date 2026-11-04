package com.verityai.security;

import com.verityai.exception.ApiException;
import com.verityai.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the per-key fixed-window rate limiter: configured limit is
 * enforced per key (IP / user), 429 carries the right error code, keys are
 * independent, and non-positive limits disable the check.
 */
class RateLimitServiceTest {

    private static void assert429(Runnable check, ErrorCode expectedCode, long expectedRetryAfter) {
        assertThatThrownBy(check::run)
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(429);
                assertThat(api.getErrorCode()).isEqualTo(expectedCode);
                assertThat(api.getRetryAfterSeconds()).isEqualTo(expectedRetryAfter);
            });
    }

    @Test
    void aiRequestsAreLimitedPerUserWithAiRateLimitedCode() {
        // constructor order: login, uploads, ai
        RateLimitService service = new RateLimitService(5, 10, 2);
        UUID user = UUID.randomUUID();

        service.checkAiRequest(user);
        service.checkAiRequest(user);

        assert429(() -> service.checkAiRequest(user), ErrorCode.AI_RATE_LIMITED, 60);
    }

    @Test
    void loginAttemptsAreLimitedPerIp() {
        RateLimitService service = new RateLimitService(1, 10, 10);

        service.checkLogin("10.0.0.1");

        assert429(() -> service.checkLogin("10.0.0.1"), ErrorCode.RATE_LIMITED, 60);
    }

    @Test
    void uploadsAreLimitedPerUser() {
        RateLimitService service = new RateLimitService(10, 1, 10);
        UUID user = UUID.randomUUID();

        service.checkUpload(user);

        assert429(() -> service.checkUpload(user), ErrorCode.RATE_LIMITED, 3600);
    }

    @Test
    void differentKeysHaveIndependentQuotas() {
        RateLimitService service = new RateLimitService(1, 1, 1);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        service.checkLogin("10.0.0.1");
        service.checkLogin("10.0.0.2");
        service.checkAiRequest(alice);
        service.checkAiRequest(bob);
        service.checkUpload(alice);
        service.checkUpload(bob);

        // each key is exhausted only for itself
        assert429(() -> service.checkLogin("10.0.0.1"), ErrorCode.RATE_LIMITED, 60);
        assert429(() -> service.checkLogin("10.0.0.2"), ErrorCode.RATE_LIMITED, 60);
        assert429(() -> service.checkAiRequest(alice), ErrorCode.AI_RATE_LIMITED, 60);
        assert429(() -> service.checkUpload(bob), ErrorCode.RATE_LIMITED, 3600);
    }

    @Test
    void nonPositiveLimitsDisableTheCheck() {
        RateLimitService service = new RateLimitService(0, -1, 0);
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 100; i++) {
            service.checkLogin("203.0.113.9");
            service.checkUpload(user);
            service.checkAiRequest(user);
        }
    }
}
