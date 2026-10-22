package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageResponse {
    private UUID id;
    private MessageRole role;
    private String content;
    private MessageStatus status;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private EvidenceStatus evidenceStatus;
    private Instant createdAt;
    private List<CitationResponse> citations;

    public enum MessageRole { USER, ASSISTANT }
    public enum MessageStatus { COMPLETED, FAILED }
    public enum EvidenceStatus { SUFFICIENT, INSUFFICIENT, NONE }

    @Data
    public static class CitationResponse {
        private UUID chunkId;
        private UUID documentId;
        private String documentName;
        private Integer pageNumber;
        private String excerpt;
        private Float rank;
        private Integer citationIndex;
    }
}