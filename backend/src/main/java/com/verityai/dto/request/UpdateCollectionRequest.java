package com.verityai.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateCollectionRequest {
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Size(max = 500, message = "Description must be 500 characters or fewer")
    private String description;
}
