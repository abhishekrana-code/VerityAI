package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All persisted AI summary sections for a document.
 *
 * <p>{@code source} lets the UI label content honestly: EXTRACTED sections are
 * facts pulled from the text, GENERATED sections are model-written prose.
 * {@code content} is the structured JSON produced by the model.</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentSummariesResponse {
    private UUID documentId;
    /** NONE | PENDING | READY | FAILED */
    private String summaryStatus;
    private String summaryError;
    private List<Section> summaries;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Section {
        private UUID id;
        /** EXECUTIVE_SUMMARY | KEY_FINDINGS | ENTITIES | DATES_AND_STATS | TOPICS | SUGGESTED_QUESTIONS */
        private String kind;
        /** EXTRACTED | GENERATED */
        private String source;
        private JsonNode content;
        private String model;
        private Instant generatedAt;
    }
}
