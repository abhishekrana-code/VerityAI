package com.verityai.dto.response;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Library-wide entity index. Rows are merged by {@code normalizedName}, so one
 * entity mentioned in three documents is a single entry with three document
 * references instead of three unrelated graph nodes.
 */
@Data
public class KnowledgeEntityIndexResponse {

    /**
     * Distinct names in this scope before the requested limit. Only an
     * unfiltered, noise-inclusive request counts every row; a filtered request
     * or one that hides noise reports the names found in the scanned window, so
     * the value is then a lower bound rather than the exact total.
     */
    private long totalEntities;

    /** Number of merged entries in this response. */
    private int returnedEntities;

    private boolean truncated;

    private List<Item> entities;

    @Data
    public static class Item {
        private String normalizedName;
        private String name;
        private String type;
        /** Mentions added up across every document. */
        private int mentionCount;
        private int documentCount;
        /** Best passage where the entity appears, so a row can be opened. */
        private String evidenceExcerpt;
        private List<DocumentRef> documents;
    }

    @Data
    public static class DocumentRef {
        private UUID id;
        private String name;
        private int mentionCount;
        private Integer firstPage;
    }
}
