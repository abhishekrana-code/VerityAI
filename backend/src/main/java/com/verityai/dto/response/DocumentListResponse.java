package com.verityai.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class DocumentListResponse {
    private List<DocumentResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
}