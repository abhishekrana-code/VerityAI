package com.verityai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeleteAccountRequest {
    /** Re-authentication: a stolen session alone must not be able to delete data. */
    @NotBlank
    private String password;
}
