package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Report index over processed documents (Phase 3 page gap). A report is the
 * persisted summary set; no new model call is made to list it.
 */
@Data
public class ReportListResponse {

    private List<Item> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    @Data
    public static class Item {
        private UUID documentId;
        private String documentName;
        private Integer pageCount;
        private Instant updatedAt;

        /** NONE | PENDING | READY | FAILED */
        private String summaryStatus;
        private String summaryError;

        private int sectionCount;
        private String model;
        private Instant generatedAt;

        private String executiveSummary;
        private List<Finding> keyFindings;
        private int entityCount;
        private int dateStatCount;
        private int topicCount;
        private int questionCount;
    }

    @Data
    public static class Finding {
        private String text;
        /** FACT | INTERPRETATION */
        private String type;
    }
}
