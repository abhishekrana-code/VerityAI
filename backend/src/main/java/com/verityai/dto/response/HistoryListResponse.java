package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Processing-history feed across the user's documents (Phase 3 page gap).
 * Every row is a persisted stage transition; nothing is synthesized.
 */
@Data
public class HistoryListResponse {

    private List<Item> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    @Data
    public static class Item {
        private UUID id;
        private UUID documentId;
        private String documentName;
        private int attempt;
        private String stage;
        private String errorCode;
        private String errorMessage;
        private Instant createdAt;
    }
}
