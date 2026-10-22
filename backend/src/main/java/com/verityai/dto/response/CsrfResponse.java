package com.verityai.dto.response;

import lombok.Data;

@Data
public class CsrfResponse {
    private String token;
    private String headerName;
    private String parameterName;
}