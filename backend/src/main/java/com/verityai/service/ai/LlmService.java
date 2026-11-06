package com.verityai.service.ai;

import com.verityai.service.retrieval.RetrievalService;

import java.util.List;
import java.util.UUID;

public interface LlmService {
    ChatResponse generate(ChatRequest request);

    /** Plain completion used for document summaries (no RAG prompt, no citations). */
    CompletionResponse complete(CompletionRequest request);

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

    record CompletionRequest(
        String system,
        String user,
        String model,
        int maxTokens
    ) {}

    record CompletionResponse(
        String content,
        int promptTokens,
        int completionTokens,
        int latencyMs,
        String model
    ) {}
}
