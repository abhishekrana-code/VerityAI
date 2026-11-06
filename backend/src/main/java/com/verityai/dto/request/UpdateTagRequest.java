package com.verityai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTagRequest {
    @NotBlank(message = "Tag name is required")
    @Size(max = 50, message = "Tag name must be 50 characters or fewer")
    private String name;

    @Size(max = 20, message = "Color must be 20 characters or fewer")
    @Pattern(regexp = "^#[0-9a-fA-F]{3,8}$|^[a-z-]{1,20}$",
        message = "Color must be a hex value like #7c3aed or a lowercase token name")
    private String color;
}
