package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
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
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer chunkCount;

    public enum DocumentStatus {
        UPLOADED, PROCESSING, READY, FAILED
    }
}