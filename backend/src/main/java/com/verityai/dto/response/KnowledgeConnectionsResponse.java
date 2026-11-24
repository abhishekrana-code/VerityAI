package com.verityai.dto.response;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Document-to-document links derived from shared entity names. Two documents
 * are connected when they mention the same normalized entity; the pair carries
 * the number of shared names and a sample so the claim can be checked.
 *
 * <p>Connections are ordered by the inverse-document-frequency weight of the
 * shared names; {@code sharedEntities} only breaks ties, so a name only the
 * pair shares outranks one present throughout the library.</p>
 */
@Data
public class KnowledgeConnectionsResponse {

    private int documentCount;

    /** Document pairs with at least one shared entity, before the limit. */
    private int pairCount;

    private boolean truncated;

    private List<Connection> connections;

    @Data
    public static class Connection {
        private UUID documentAId;
        private String documentAName;
        private UUID documentBId;
        private String documentBName;
        private int sharedEntities;
        /** Sum of the IDF weights of the shared names; the list is ordered by it. */
        private double weight;
        private List<String> sampleEntities;
    }
}
