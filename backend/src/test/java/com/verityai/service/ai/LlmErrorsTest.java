package com.verityai.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorsTest {

    @Test
    void transientProviderFailuresInviteARetry() {
        assertThat(LlmErrors.friendlyMessage(new OpenAiCompatibleLlmService.LlmProviderException("rate limited", 429)))
            .isEqualTo(LlmErrors.BUSY);
        assertThat(LlmErrors.friendlyMessage(new OpenAiCompatibleLlmService.LlmProviderException("provider error", 503)))
            .isEqualTo(LlmErrors.BUSY);
        // 0 covers timeouts and dropped connections in the transport.
        assertThat(LlmErrors.friendlyMessage(new OpenAiCompatibleLlmService.LlmProviderException("network error", 0)))
            .isEqualTo(LlmErrors.BUSY);
    }

    @Test
    void rejectedRequestsDoNotPromiseARetryWillFixIt() {
        assertThat(LlmErrors.friendlyMessage(new OpenAiCompatibleLlmService.LlmProviderException("invalid api key", 401)))
            .isEqualTo(LlmErrors.UNAVAILABLE);
        assertThat(LlmErrors.friendlyMessage(new OpenAiCompatibleLlmService.LlmProviderException("unexpected", 400)))
            .isEqualTo(LlmErrors.UNAVAILABLE);
    }

    @Test
    void unknownFailuresNeverEchoTheirMessage() {
        assertThat(LlmErrors.friendlyMessage(new RuntimeException("connect to 10.0.0.5 refused")))
            .isEqualTo(LlmErrors.UNAVAILABLE);
    }
}
