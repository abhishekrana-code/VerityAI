package com.verityai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AskQuestionRequest {
    @NotBlank
    @Size(max = 4000)
    private String question;

    private List<UUID> documentIds;
}