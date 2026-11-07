package com.verityai.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class SearchRequest {

    @NotBlank
    @Size(max = 2000)
    private String query;

    /** Optional scope: search only these documents (all owned by the caller). */
    @Size(max = 50)
    private List<UUID> documentIds;

    /** Result count cap; server enforces its configured maximum. */
    @Min(1)
    private Integer limit;
}
