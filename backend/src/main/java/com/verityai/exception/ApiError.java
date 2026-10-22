package com.verityai.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp = Instant.now();
    private int status;
    private String code;
    private String message;
    private String path;
    private String requestId;
    private List<FieldError> fieldErrors;
    private Map<String, Object> metadata;

    public static ApiError of(int status, ErrorCode code, String message, String path, String requestId) {
        return new ApiError(Instant.now(), status, code.getCode(), message, path, requestId, null, null);
    }

    public static ApiError of(int status, ErrorCode code, String message, String path, String requestId, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status, code.getCode(), message, path, requestId, fieldErrors, null);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldError {
        private String field;
        private String message;
        private Object rejectedValue;
    }
}