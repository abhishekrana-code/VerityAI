package com.verityai.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

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
        return ResponseEntity.status(ex.getStatus()).body(error);
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
        ApiError error = ApiError.of(ex.getStatusCode().value(), ErrorCode.INTERNAL_ERROR, ex.getReason(), request.getRequestURI(), getRequestId());
        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ApiError error = ApiError.of(401, ErrorCode.UNAUTHENTICATED, "Invalid credentials", request.getRequestURI(), getRequestId());
        return ResponseEntity.status(401).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        ApiError error = ApiError.of(500, ErrorCode.INTERNAL_ERROR, "Internal server error", request.getRequestURI(), getRequestId());
        return ResponseEntity.status(500).body(error);
    }
}