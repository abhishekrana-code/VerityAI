package com.verityai.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private String getRequestId() {
        return MDC.get("requestId");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ApiError error = ApiError.of(
            ex.getStatus(),
            ex.getErrorCode(),
            ex.getMessage(),
            request.getRequestURI(),
            getRequestId(),
            ex.getFieldErrors() != null ? List.of(ex.getFieldErrors()) : null
        );
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            response.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        }
        return response.body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
            .collect(Collectors.toList());
        ApiError error = ApiError.of(400, ErrorCode.VALIDATION_ERROR, "Validation failed", request.getRequestURI(), getRequestId(), fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        ApiError error = ApiError.of(400, ErrorCode.VALIDATION_ERROR, "Malformed request body", request.getRequestURI(), getRequestId());
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // A malformed UUID (or other typed value) in a path or query parameter is a
        // client error; without this it falls through to the generic 500 handler.
        ApiError error = ApiError.of(400, ErrorCode.VALIDATION_ERROR,
            "Invalid value for '" + ex.getName() + "'",
            request.getRequestURI(), getRequestId());
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ApiError error = ApiError.of(405, ErrorCode.METHOD_NOT_ALLOWED,
            "Method " + ex.getMethod() + " is not supported for this endpoint",
            request.getRequestURI(), getRequestId());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        String contentType = ex.getContentType() == null ? "" : ": " + ex.getContentType();
        ApiError error = ApiError.of(415, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported media type" + contentType,
            request.getRequestURI(), getRequestId());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        ApiError error = ApiError.of(413, ErrorCode.FILE_TOO_LARGE, "File size exceeds limit", request.getRequestURI(), getRequestId());
        return ResponseEntity.status(413).body(error);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = ex.getMostSpecificCause().getMessage();
        ApiError error = ApiError.of(409, ErrorCode.CONFLICT, "Data integrity violation: " + message, request.getRequestURI(), getRequestId());
        return ResponseEntity.status(409).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        String message = ex.getReason();
        if (message == null || message.isBlank()) {
            HttpStatus resolved = HttpStatus.resolve(status);
            message = resolved != null ? resolved.getReasonPhrase() : "Request failed";
        }
        ApiError error = ApiError.of(status, errorCodeFor(status), message, request.getRequestURI(), getRequestId());
        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    /**
     * Clients key off the error code, so a 4xx must never be reported as
     * INTERNAL_ERROR. Unknown client-error statuses fall back to a validation
     * code rather than claiming the server failed.
     */
    private static ErrorCode errorCodeFor(int status) {
        return switch (status) {
            case 400 -> ErrorCode.VALIDATION_ERROR;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.CONFLICT;
            case 413 -> ErrorCode.FILE_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
        };
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ApiError error = ApiError.of(401, ErrorCode.UNAUTHENTICATED, "Invalid credentials", request.getRequestURI(), getRequestId());
        return ResponseEntity.status(401).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        // Never swallow an unexpected failure silently: without this a 500 leaves
        // no trace in the logs and cannot be diagnosed after the fact.
        log.error("Unhandled exception on {} {} (requestId={})",
            request.getMethod(), request.getRequestURI(), getRequestId(), ex);
        ApiError error = ApiError.of(500, ErrorCode.INTERNAL_ERROR, "Internal server error", request.getRequestURI(), getRequestId());
        return ResponseEntity.status(500).body(error);
    }
}