package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TagResponse {
    private UUID id;
    private String name;
    private String color;
    private Instant createdAt;
    /** How many live documents carry this tag (for the filter sidebar). */
    private Long documentCount;
}
