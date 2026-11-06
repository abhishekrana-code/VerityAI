package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentResponse {
    private UUID id;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private Integer pageCount;
    private DocumentStatus status;
    private String sha256;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer chunkCount;

    /** AI summary lifecycle: NONE | PENDING | READY | FAILED. */
    private String summaryStatus;
    /** Human-readable reason when summaryStatus is FAILED. */
    private String summaryError;
    /** Latest processing stage, when a processing history exists. */
    private String processingStage;
    /** Tags attached to this document (populated on list/detail reads). */
    private List<TagResponse> tags;
    /** Collections containing this document (populated on list/detail reads). */
    private List<CollectionRef> collections;

    public enum DocumentStatus {
        UPLOADED, PROCESSING, READY, FAILED
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CollectionRef {
        private UUID id;
        private String name;
    }
}
