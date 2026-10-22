package com.verityai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateConversationRequest {
    @NotBlank
    @Size(max = 500)
    private String title;

    private List<UUID> documentIds;
}