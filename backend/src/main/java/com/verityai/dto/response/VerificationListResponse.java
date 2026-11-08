package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verification index over the user's assistant messages (Phase 3 page gap).
 * Rows are stored claim verifications; no model call is made to read this.
 */
@Data
public class VerificationListResponse {

    private List<Item> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    @Data
    public static class Item {
        private UUID messageId;
        private UUID conversationId;
        private String conversationTitle;
        private String contentExcerpt;
        private Instant createdAt;

        private String messageModel;
        private String evidenceStatus;
        private int citationCount;

        /** NOT_REQUESTED | PARTIAL | COMPLETED */
        private String status;
        private Double coverage;
        private String verificationModel;
        private Instant verifiedAt;

        /** Claims found in the message text. */
        private int totalClaims;
        /** Claims actually analyzed (stored rows; capped by the verifier). */
        private int analyzedClaims;
        private int classifiedClaims;
        private int invalidReferenceCount;

        /** SUPPORTED / PARTIALLY_SUPPORTED / UNSUPPORTED / CONFLICTING counts. */
        private Map<String, Integer> supportCounts;
    }
}
