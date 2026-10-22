package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationResponse {
    private UUID id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant archivedAt;
    private int messageCount;
    private List<DocumentSummary> documents;

    @Data
    public static class DocumentSummary {
        private UUID id;
        private String originalFilename;
    }
}