package com.verityai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PasswordResetConfirmRequest {
    @NotBlank
    private String token;

    @NotBlank
    @Size(min = 8, max = 128)
    private String newPassword;
}
