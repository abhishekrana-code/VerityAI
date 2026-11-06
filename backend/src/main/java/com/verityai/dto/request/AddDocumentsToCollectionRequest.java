package com.verityai.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AddDocumentsToCollectionRequest {
    @NotEmpty(message = "documentIds is required")
    @Size(max = 100, message = "Cannot add more than 100 documents at once")
    private List<UUID> documentIds;
}
