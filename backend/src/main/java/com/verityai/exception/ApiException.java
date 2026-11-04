package com.verityai.exception;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int status;
    private final ApiError.FieldError[] fieldErrors;
    /** Seconds a client should wait before retrying (429 responses), or null. */
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode errorCode, String message, int status) {
        this(errorCode, message, status, null, null);
    }

    public ApiException(ErrorCode errorCode, String message, int status, ApiError.FieldError[] fieldErrors) {
        this(errorCode, message, status, fieldErrors, null);
    }

    public ApiException(ErrorCode errorCode, String message, int status, ApiError.FieldError[] fieldErrors,
                        Long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.fieldErrors = fieldErrors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException validation(String message, ApiError.FieldError[] fieldErrors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message, 400, fieldErrors);
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message, 400);
    }

    public static ApiException unauthenticated(String message) {
        return new ApiException(ErrorCode.UNAUTHENTICATED, message, 401);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message, 403);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message, 404);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message, 409);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(ErrorCode.RATE_LIMITED, message, 429);
    }

    public static ApiException internal(String message) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, message, 500);
    }
}