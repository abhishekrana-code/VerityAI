package com.verityai.dto.response;

import lombok.Data;

import java.util.UUID;

@Data
public class DocumentUploadResponse {
    private UUID id;
    private String originalFilename;
    private DocumentStatus status;

    public enum DocumentStatus {
        UPLOADED, PROCESSING, READY, FAILED
    }
}