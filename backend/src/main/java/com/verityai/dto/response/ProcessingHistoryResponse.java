package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistent processing history for one document (newest first). */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessingHistoryResponse {
    private UUID documentId;
    /** Latest stage across all attempts — the document's current position. */
    private String currentStage;
    /** Highest attempt number seen (increments on retry/restart recovery). */
    private int currentAttempt;
    private List<Event> events;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Event {
        private UUID id;
        private int attempt;
        private String stage;
        private String errorCode;
        private String errorMessage;
        private Instant createdAt;
    }
}
