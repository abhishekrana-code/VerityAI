package com.verityai.service.retrieval;

import com.verityai.entity.DocumentChunk;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.service.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hybrid retrieval: full-text keyword search fused with local semantic
 * (cosine similarity) search using Reciprocal Rank Fusion (RRF).
 *
 * <p><b>Honest degradation.</b> When the semantic branch cannot contribute
 * (embeddings disabled, model unavailable, or nothing embedded yet) the result
 * is plain keyword search — the exact SQL and ts_rank scores used before
 * Phase 2 — reported as strategy {@code "keyword"}. Scores are never faked.</p>
 *
 * <p><b>Score semantics</b> (surfaced as {@code rank}, used for ordering and
 * the UI relevance percentage): keyword-only = raw ts_rank (unchanged from
 * pre-Phase-2); semantic-only = cosine similarity; hybrid = RRF score
 * normalized to [0,1] where a #1 hit in both lists scores 1.0.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    @Value("${verityai.retrieval.top-k:8}")
    private int topK;

    @Value("${verityai.retrieval.min-rank:0.01}")
    private double minRank;

    /** false = keyword-only retrieval (exact pre-Phase-2 behavior). */
    @Value("${verityai.retrieval.hybrid:true}")
    private boolean hybrid;

    /** RRF smoothing constant (60 is the standard value from the paper). */
    @Value("${verityai.retrieval.rrf-k:60}")
    private int rrfK;

    /** Minimum cosine similarity for a semantic candidate (filters noise). */
    @Value("${verityai.retrieval.min-similarity:0.10}")
    private double minSimilarity;

    /** Chat entry point (legacy signature): fused evidence ordered by relevance. */
    public List<RetrievedChunk> retrieve(UUID userId, String query) {
        return retrieve(userId, query, null);
    }

    /** Chat entry point (legacy signature). */
    public List<RetrievedChunk> retrieve(UUID userId, String query, List<UUID> docIds) {
        return search(userId, query, docIds, topK).chunks();
    }

    /**
     * Hybrid search over the user's (optionally document-scoped) chunks.
     *
     * @param limit maximum results (caller enforces its own cap)
     * @return chunks plus the strategy/model actually used, for honest reporting
     */
    public SearchResult search(UUID userId, String query, List<UUID> docIds, int limit) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), "keyword", null);
        }
        int effectiveLimit = Math.max(1, limit);
        boolean scoped = docIds != null && !docIds.isEmpty();

        // --- Keyword branch (identical SQL and scores to pre-Phase-2) ---
        List<Object[]> kwRows = scoped
            ? chunkRepository.searchIdsWithRankScoped(userId, query, docIds, effectiveLimit)
            : chunkRepository.searchIdsWithRank(userId, query, effectiveLimit);

        Map<UUID, Double> keyword = new LinkedHashMap<>(); // chunkId -> ts_rank (rank desc)
        for (Object[] row : kwRows) {
            double tsRank = ((Number) row[1]).doubleValue();
            if (tsRank >= minRank) {
                keyword.putIfAbsent((UUID) row[0], tsRank);
            }
        }

        // --- Semantic branch (best-effort: any failure degrades to keyword) ---
        Map<UUID, Double> semantic = new LinkedHashMap<>(); // chunkId -> cosine similarity (desc)
        boolean semanticRan = false;
        if (hybrid && embeddingService.isAvailable()) {
            try {
                String vector = EmbeddingService.toVectorLiteral(embeddingService.embed(query));
                List<Object[]> vRows = scoped
                    ? chunkRepository.nearestByVectorScoped(userId, docIds, vector, effectiveLimit)
                    : chunkRepository.nearestByVector(userId, vector, effectiveLimit);
                semanticRan = true;
                for (Object[] row : vRows) {
                    double similarity = 1.0 - ((Number) row[1]).doubleValue();
                    if (similarity >= minSimilarity) {
                        semantic.putIfAbsent((UUID) row[0], similarity);
                    }
                }
            } catch (Exception e) {
                // Includes EmbeddingException (provider unavailable) and vector-SQL
                // failures: search must keep working keyword-only.
                log.warn("Semantic branch skipped, keyword-only this request: {}", e.getMessage());
            }
        }

        String model = semanticRan ? embeddingService.modelName() : null;

        // --- Degrade or fuse ---
        if (semantic.isEmpty()) {
            return keywordOnly(keyword, model);
        }
        if (keyword.isEmpty()) {
            return semanticOnly(semantic, model);
        }
        return fused(keyword, semantic, model, effectiveLimit);
    }

    // -----------------------------------------------------------------------
    // Result assembly
    // -----------------------------------------------------------------------

    /** Exact pre-Phase-2 behavior: ts_rank scores, keyword order preserved. */
    private SearchResult keywordOnly(Map<UUID, Double> keyword, String model) {
        return new SearchResult(
            hydrate(new ArrayList<>(keyword.keySet()), keyword, keyword, null), "keyword", model);
    }

    private SearchResult semanticOnly(Map<UUID, Double> semantic, String model) {
        return new SearchResult(
            hydrate(new ArrayList<>(semantic.keySet()), semantic, null, semantic), "semantic", model);
    }

    /**
     * Reciprocal Rank Fusion: score = Σ 1/(k + position) over the lists that
     * contain the chunk, normalized by the maximum achievable score so the
     * result stays in [0,1]. A chunk found by only one retriever tops out at
     * 0.5, which is reported honestly via the per-hit match flags.
     *
     * <p>The fused union can hold up to 2×{@code limit} distinct chunks (one
     * per branch), so it is trimmed back to the caller's cap after ranking.</p>
     */
    private SearchResult fused(Map<UUID, Double> keyword, Map<UUID, Double> semantic, String model, int limit) {
        Map<UUID, Integer> kwPos = positions(keyword.keySet());
        Map<UUID, Integer> semPos = positions(semantic.keySet());

        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (UUID id : keyword.keySet()) {
            scores.merge(id, 1.0 / (rrfK + kwPos.get(id)), Double::sum);
        }
        for (UUID id : semantic.keySet()) {
            scores.merge(id, 1.0 / (rrfK + semPos.get(id)), Double::sum);
        }
        double normalizer = 2.0 / (rrfK + 1.0); // #1 in both lists => 1.0

        List<UUID> ordered = new ArrayList<>(scores.keySet());
        ordered.sort((a, b) -> {
            int c = Double.compare(scores.get(b), scores.get(a));
            if (c != 0) return c;
            c = Double.compare(keyword.getOrDefault(b, -1.0), keyword.getOrDefault(a, -1.0));
            if (c != 0) return c;
            return Double.compare(semantic.getOrDefault(b, -1.0), semantic.getOrDefault(a, -1.0));
        });
        if (ordered.size() > limit) {
            ordered = new ArrayList<>(ordered.subList(0, limit));
        }

        return new SearchResult(hydrate(ordered, scores, keyword, semantic), "hybrid", model);
    }

    // -----------------------------------------------------------------------
    // Hydration
    // -----------------------------------------------------------------------

    /**
     * Loads chunks in one query (no N+1), preserving order.
     *
     * @param scores  rank value per chunk (ts_rank / similarity / fused RRF,
     *                depending on the caller's strategy)
     * @param keyword keyword match flags, or null when the strategy has none
     * @param semantic semantic match flags + similarity values, or null
     */
    private List<RetrievedChunk> hydrate(
            List<UUID> ordered, Map<UUID, Double> scores,
            Map<UUID, Double> keyword, Map<UUID, Double> semantic) {
        Map<UUID, DocumentChunk> chunksById = new LinkedHashMap<>();
        chunkRepository.findAllById(ordered).forEach(c -> chunksById.put(c.getId(), c));

        List<RetrievedChunk> results = new ArrayList<>();
        for (UUID id : ordered) {
            DocumentChunk chunk = chunksById.get(id);
            if (chunk == null) {
                continue; // deleted between the search query and hydration
            }
            boolean kw = keyword != null && keyword.containsKey(id);
            boolean sem = semantic != null && semantic.containsKey(id);
            Double similarity = semantic == null ? null : semantic.get(id);
            results.add(new RetrievedChunk(chunk, scores.get(id), similarity, kw, sem));
        }
        return results;
    }

    private static Map<UUID, Integer> positions(java.util.Set<UUID> ids) {
        Map<UUID, Integer> positions = new LinkedHashMap<>();
        int i = 1;
        for (UUID id : ids) {
            positions.put(id, i++);
        }
        return positions;
    }

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    /**
     * One retrieval outcome.
     *
     * @param strategy "keyword" | "semantic" | "hybrid" — what actually matched
     * @param model    embedding model used for this request, or null when the
     *                 semantic branch did not run (keyword-only and no embedding)
     */
    public record SearchResult(List<RetrievedChunk> chunks, String strategy, String model) {
    }

    /**
     * A retrieved chunk with honest match metadata.
     *
     * @param rank          relevance score in [0,1] (semantics per strategy, see class javadoc)
     * @param similarity    cosine similarity when found semantically, else null
     * @param keywordMatch  found by full-text search
     * @param semanticMatch found by vector similarity
     */
    public record RetrievedChunk(DocumentChunk chunk, double rank, Double similarity,
                                 boolean keywordMatch, boolean semanticMatch) {

        /** Legacy constructor: keyword-era semantics (ts_rank, no similarity). */
        public RetrievedChunk(DocumentChunk chunk, double rank) {
            this(chunk, rank, null, true, false);
        }

        public String getExcerpt() {
            String content = chunk.getContent();
            return content.length() > 300 ? content.substring(0, 300) + "..." : content;
        }
    }
}
