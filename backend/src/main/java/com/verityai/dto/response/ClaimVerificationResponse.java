package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of claim-level citation verification for one assistant message.
 *
 * <p>{@code coverage} is deterministic: the fraction of analyzed claims that
 * cite at least one valid source. {@code supportClass} is an AI grounding
 * assessment — not a calibrated probability.</p>
 */
@Data
public class ClaimVerificationResponse {

    public enum Status {
        /** Never verified (no stored results). */
        NOT_REQUESTED,
        /** Every analyzed claim classified. */
        COMPLETED,
        /** Some claims classified; the rest are recorded without a class. */
        PARTIAL,
        /** Nothing could be classified (e.g. model error); nothing stored. */
        FAILED
    }

    private UUID messageId;

    private Status status;

    /** Deterministic fraction of analyzed claims citing ≥1 valid source (null when no claims). */
    private Double coverage;

    /** Model that performed the classification (null when no model ran). */
    private String model;

    private Instant verifiedAt;

    /** Claims found in the message text (may exceed claims.size() when capped). */
    private int totalClaims;

    /** Why verification failed (only when status = FAILED). */
    private String error;

    private List<ClaimResult> claims;

    private List<InvalidReference> invalidReferences;

    @Data
    public static class ClaimResult {
        private int claimIndex;
        private int sentenceIndex;
        private String claimText;

        /** SUPPORTED | PARTIALLY_SUPPORTED | UNSUPPORTED | CONFLICTING; null = not classified. */
        private String supportClass;

        /** Short human-readable reason from the model (or a deterministic rule). */
        private String explanation;

        /** 0-based citation indexes this claim relies on. */
        private List<Integer> citationIndexes;

        /** True when the claim references an [Sn] that does not exist in the evidence. */
        private boolean hasInvalidCitation;
    }

    @Data
    public static class InvalidReference {
        private int claimIndex;
        private String claimText;

        /** The out-of-range S number as written in the text (1-based). */
        private int reference;
    }
}
