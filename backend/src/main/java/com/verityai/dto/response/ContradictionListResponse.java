package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stored contradiction findings (Feature 8). Reading this never calls a model.
 */
@Data
public class ContradictionListResponse {

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

        /** One of the five approved classes. */
        private String contradictionType;
        /** LOW | MEDIUM | HIGH — materiality assessment, not a probability. */
        private String severity;
        private String subjectEntity;
        private String explanation;
        private String model;
        private Instant detectedAt;

        private String claimA;
        private UUID documentAId;
        private String documentAName;
        private Integer pageA;
        private UUID chunkAId;

        private String claimB;
        private UUID documentBId;
        private String documentBName;
        private Integer pageB;
        private UUID chunkBId;
    }
}
