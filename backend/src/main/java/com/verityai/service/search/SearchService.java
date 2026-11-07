package com.verityai.service.search;

import com.verityai.dto.request.SearchRequest;
import com.verityai.dto.response.SearchResponse;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.service.retrieval.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Semantic/hybrid search use case: ownership check on the requested scope,
 * bounded result count, and mapping to the API shape.
 *
 * <p>Transactional so lazy {@code chunk.document} proxies resolve while
 * mapping (open-in-view is disabled); retrieval itself is read-only.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final DocumentRepository documentRepository;
    private final RetrievalService retrievalService;

    @Value("${verityai.search.max-results:30}")
    private int maxResults;

    @Value("${verityai.search.default-results:20}")
    private int defaultResults;

    @Transactional(readOnly = true)
    public SearchResponse search(User user, SearchRequest request) {
        String query = request.getQuery() == null ? "" : request.getQuery().trim();
        if (query.isEmpty()) {
            throw ApiException.validation("Query must not be blank");
        }

        List<UUID> docIds = resolveScope(user, request.getDocumentIds());

        int limit = request.getLimit() == null ? defaultResults : request.getLimit();
        limit = Math.max(1, Math.min(limit, maxResults));

        RetrievalService.SearchResult result = retrievalService.search(user.getId(), query, docIds, limit);

        SearchResponse response = new SearchResponse();
        response.setQuery(query);
        response.setStrategy(result.strategy());
        response.setModel(result.model());

        List<SearchResponse.SearchHit> hits = new ArrayList<>();
        for (RetrievalService.RetrievedChunk rc : result.chunks()) {
            DocumentChunk chunk = rc.chunk();
            SearchResponse.SearchHit hit = new SearchResponse.SearchHit();
            hit.setChunkId(chunk.getId());
            hit.setDocumentId(chunk.getDocument().getId());
            hit.setDocumentName(chunk.getDocument().getOriginalFilename());
            hit.setPageNumber(chunk.getPageNumber());
            hit.setChunkIndex(chunk.getChunkIndex());
            hit.setContent(chunk.getContent());
            hit.setExcerpt(rc.getExcerpt());
            hit.setRank(rc.rank());
            hit.setSimilarity(rc.similarity());
            hit.setKeywordMatch(rc.keywordMatch());
            hit.setSemanticMatch(rc.semanticMatch());
            hits.add(hit);
        }
        response.setResults(hits);
        response.setCount(hits.size());
        return response;
    }

    /**
     * Validates document scope: every requested document must exist and belong
     * to the caller (same explicit-403 style as conversation scoping). Empty or
     * null scope = search all of the user's documents.
     */
    private List<UUID> resolveScope(User user, List<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        List<Document> docs = documentRepository.findAllById(requested);
        for (Document doc : docs) {
            if (!doc.getUser().getId().equals(user.getId())) {
                throw ApiException.forbidden("Document access denied: " + doc.getId());
            }
        }
        // Ids that do not exist match nothing in SQL — harmless, returns fewer hits.
        return requested;
    }
}
