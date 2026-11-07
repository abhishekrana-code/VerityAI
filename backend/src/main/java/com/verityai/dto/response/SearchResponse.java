package com.verityai.dto.response;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class SearchResponse {

    private String query;

    /** What actually matched: "keyword" | "semantic" | "hybrid". */
    private String strategy;

    /** Embedding model used for this request; null when the semantic branch did not run. */
    private String model;

    private int count;

    private List<SearchHit> results;

    @Data
    public static class SearchHit {
        private UUID chunkId;
        private UUID documentId;
        private String documentName;
        private Integer pageNumber;
        private Integer chunkIndex;
        private String content;
        private String excerpt;

        /** Relevance in [0,1]; semantics depend on strategy (see RetrievalService). */
        private double rank;

        /** Cosine similarity when found semantically; null for keyword-only hits. */
        private Double similarity;

        private boolean keywordMatch;
        private boolean semanticMatch;
    }
}
