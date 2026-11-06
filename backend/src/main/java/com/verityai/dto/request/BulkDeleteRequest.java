package com.verityai.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Bulk delete with explicit id list — the UI confirms before sending this. */
@Data
public class BulkDeleteRequest {
    @NotEmpty(message = "documentIds is required")
    @Size(max = 100, message = "Cannot delete more than 100 documents at once")
    private List<UUID> documentIds;
}
