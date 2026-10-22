package com.verityai.service.ai;

import com.verityai.service.retrieval.RetrievalService;

import java.util.List;
import java.util.UUID;

public interface LlmService {
    ChatResponse generate(ChatRequest request);

    record ChatRequest(
        String question,
        List<RetrievalService.RetrievedChunk> evidence,
        String model,
        int maxTokens
    ) {}

    record ChatResponse(
        String content,
        int promptTokens,
        int completionTokens,
        int latencyMs,
        String model,
        List<Integer> citedIndices
    ) {}
}