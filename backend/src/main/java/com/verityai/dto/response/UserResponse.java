package com.verityai.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
    private UUID id;
    private String email;
    private String displayName;
    private String role;
    private Instant createdAt;
    private Instant lastLoginAt;
}