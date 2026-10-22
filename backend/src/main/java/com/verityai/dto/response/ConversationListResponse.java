package com.verityai.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class ConversationListResponse {
    private List<ConversationResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
}