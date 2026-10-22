package com.verityai.service.retrieval;

import com.verityai.entity.DocumentChunk;
import com.verityai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final DocumentChunkRepository chunkRepository;

    @Value("${verityai.retrieval.top-k:8}")
    private int topK;

    @Value("${verityai.retrieval.min-rank:0.1}")
    private double minRank;

    public List<RetrievedChunk> retrieve(UUID userId, String query) {
        List<Object[]> results = chunkRepository.fullTextSearchWithRank(userId, query, topK);

        return results.stream()
            .map(row -> {
                DocumentChunk chunk = (DocumentChunk) row[0];
                Double rank = ((Number) row[1]).doubleValue();
                if (rank < minRank) return null;
                return new RetrievedChunk(chunk, rank);
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    public record RetrievedChunk(DocumentChunk chunk, double rank) {
        public String getExcerpt() {
            String content = chunk.getContent();
            return content.length() > 300 ? content.substring(0, 300) + "..." : content;
        }
    }
}