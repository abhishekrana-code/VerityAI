package com.verityai.service.ai;

/**
 * One user-facing message for LLM failures.
 *
 * <p>Provider text can carry status codes, endpoint hosts or fragments of a
 * key, and none of it is actionable for a reader. The technical cause is logged
 * server-side; the browser only ever sees these messages.</p>
 */
public final class LlmErrors {

    /** Worth retrying: rate limit, 5xx, timeout, dropped connection (status 0). */
    public static final String BUSY = "The AI service is busy. Try again in a minute.";

    /** A retry will not fix it by itself: bad key, rejected request, bad response. */
    public static final String UNAVAILABLE =
        "The AI service is not available right now. Try again later, or tell the administrator.";

    private LlmErrors() {
    }

    public static String friendlyMessage(Throwable error) {
        if (error instanceof OpenAiCompatibleLlmService.LlmProviderException provider) {
            int status = provider.getStatusCode();
            return status == 429 || status >= 500 || status == 0 ? BUSY : UNAVAILABLE;
        }
        return UNAVAILABLE;
    }
}
