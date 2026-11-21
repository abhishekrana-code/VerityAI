package com.verityai.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
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
}
