package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge-graph read model (Phase 3, Feature 7).
 *
 * <p>Nodes and edges are evidence-backed mentions from the user's own
 * documents. {@code relationType=CO_OCCURS} means the two entities appeared in
 * the same passage; it is not a claim that they are semantically related.</p>
 */
@Data
public class KnowledgeGraphResponse {

    /** Null for a library-wide graph. */
    private UUID documentId;

    private String documentName;

    /** DOCUMENT or LIBRARY. */
    private String scope;

    /** NONE | PENDING | READY | FAILED for a document scope. */
    private String status;

    private String error;

    private Instant generatedAt;

    private long entityCount;

    private long relationCount;

    private int documentCount;

    /** Number of nodes in this response (after the requested limit). */
    private int returnedEntities;

    private boolean truncated;

    private List<Node> nodes;

    private List<Edge> edges;

    @Data
    public static class Node {
        private UUID id;
        private String name;
        private String type;
        private int mentionCount;
        private UUID documentId;
        private String documentName;
        private Integer firstPage;
        private String evidenceExcerpt;
    }

    @Data
    public static class Edge {
        private UUID id;
        private UUID source;
        private UUID target;
        private String relationType;
        private int weight;
        private UUID documentId;
        private Integer firstPage;
        private String evidenceExcerpt;
    }
}
