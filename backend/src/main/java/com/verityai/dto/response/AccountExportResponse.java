package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything the account owns, in one JSON document: the account itself,
 * document metadata (never the PDF bytes), every conversation with its
 * messages and citations, and the stored check results.
 */
@Data
public class AccountExportResponse {

    private String formatVersion = "1";
    private Instant exportedAt;
    private Account account;
    private List<DocumentEntry> documents = new ArrayList<>();
    private List<ConversationEntry> conversations = new ArrayList<>();
    private Checks checks = new Checks();

    @Data
    public static class Account {
        private UUID id;
        private String email;
        private String displayName;
        private Instant createdAt;
    }

    @Data
    public static class DocumentEntry {
        private UUID id;
        private String filename;
        private Long sizeBytes;
        private Integer pageCount;
        private String status;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    public static class ConversationEntry {
        private UUID id;
        private String title;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant archivedAt;
        private List<MessageEntry> messages = new ArrayList<>();
    }

    @Data
    public static class MessageEntry {
        private UUID id;
        private String role;
        private String content;
        private String status;
        private String model;
        private String evidenceStatus;
        private Instant createdAt;
        private List<CitationEntry> citations = new ArrayList<>();
    }

    @Data
    public static class CitationEntry {
        private UUID documentId;
        private String documentFilename;
        private Integer pageNumber;
        private String excerpt;
        private Integer citationIndex;
    }

    @Data
    public static class Checks {
        private List<ClaimCheck> claims = new ArrayList<>();
        private List<ContradictionEntry> contradictions = new ArrayList<>();
    }

    @Data
    public static class ClaimCheck {
        private UUID messageId;
        private Integer claimIndex;
        private String claim;
        private String supportClass;
        private String explanation;
        private Instant verifiedAt;
    }

    @Data
    public static class ContradictionEntry {
        private UUID id;
        private String documentA;
        private String documentB;
        private String claimA;
        private String claimB;
        private Integer pageA;
        private Integer pageB;
        private String type;
        private String severity;
        private String explanation;
        private Instant detectedAt;
    }
}
