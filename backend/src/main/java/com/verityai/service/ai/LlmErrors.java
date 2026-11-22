package com.verityai.service.ai;

import java.net.http.HttpTimeoutException;

/**
 * Turns provider failures into one user-facing message.
 *
 * <p>Raw provider text can carry status codes, endpoint hosts or fragments of
 * a key, and it is not something a reader can act on. The technical cause is
 * logged server-side; the browser only ever sees this message.</p>
 */
public final class LlmErrors {

    /** Transient: rate limit, 5xx, timeout or a dropped connection. */
    public static final String BUSY = "The AI service is busy. Try again in a minute.";

    /** Anything else (bad key, rejected request, unparsable response). */
    public static final String UNAVAILABLE =
        "The AI service could not complete this request. Try again, or check the server's LLM configuration.";

    private LlmErrors() {
    }

    public static String friendlyMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return BUSY;
            }
            if (cause instanceof OpenAiCompatibleLlmService.LlmProviderException provider) {
                int status = provider.getStatusCode();
                return status == 429 || status >= 500 || status == 0 ? BUSY : UNAVAILABLE;
            }
        }
        return UNAVAILABLE;
    }
}
