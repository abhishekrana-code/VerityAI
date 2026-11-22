package com.verityai.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @Test
    void wrongMethodIs405NotA500() {
        var response = handler.handleMethodNotAllowed(
            new HttpRequestMethodNotSupportedException("GET"), request("GET", "/api/v1/search"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(405);
        assertThat(response.getBody().getCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().getMessage()).contains("GET");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/search");
    }

    @Test
    void wrongMediaTypeIs415NotA500() {
        var response = handler.handleUnsupportedMediaType(
            new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON, List.of(MediaType.MULTIPART_FORM_DATA)),
            request("POST", "/api/v1/documents"));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(415);
        assertThat(response.getBody().getCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(response.getBody().getMessage()).contains("application/json");
    }

    @Test
    void malformedUuidParameterIs400NotA500() {
        var ex = new MethodArgumentTypeMismatchException(
            "not-a-uuid", java.util.UUID.class, "documentId", null,
            new IllegalArgumentException("Invalid UUID string"));

        var response = handler.handleTypeMismatch(ex, request("GET", "/api/v1/reports"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getMessage()).contains("documentId");
    }

    @Test
    void responseStatus4xxKeepsItsOwnErrorCode() {
        var notFound = handler.handleResponseStatus(
            new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Document not found"),
            request("GET", "/api/v1/documents/abc"));

        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody()).isNotNull();
        assertThat(notFound.getBody().getCode()).isEqualTo("NOT_FOUND");
        assertThat(notFound.getBody().getMessage()).isEqualTo("Document not found");

        var rateLimited = handler.handleResponseStatus(
            new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS),
            request("POST", "/api/v1/search"));

        assertThat(rateLimited.getBody()).isNotNull();
        assertThat(rateLimited.getBody().getCode()).isEqualTo("RATE_LIMITED");
        // A reason phrase is supplied when the exception carries none.
        assertThat(rateLimited.getBody().getMessage()).isEqualTo("Too Many Requests");
    }

    @Test
    void responseStatus5xxStillReportsInternalError() {
        var response = handler.handleResponseStatus(
            new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Upstream down"),
            request("GET", "/api/v1/documents"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void unexpectedExceptionsStillReturnTheGenericEnvelope() {
        var response = handler.handleGeneric(
            new IllegalStateException("boom"), request("GET", "/api/v1/documents"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        // The response must not leak the internal message to the client.
        assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
    }
}
