package com.verityai.repository;

import com.verityai.entity.DocumentChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    Page<DocumentChunk> findByDocumentId(UUID documentId, Pageable pageable);

    @Query(value = """
        SELECT dc.id, ts_rank_cd(dc.content_tsv, verity_or_tsquery(:query)) AS rank
        FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.user_id = :userId
          AND d.deleted_at IS NULL
          AND dc.content_tsv @@ verity_or_tsquery(:query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchIdsWithRank(@Param("userId") UUID userId, @Param("query") String query, @Param("limit") int limit);

    @Query(value = """
        SELECT dc.id, ts_rank_cd(dc.content_tsv, verity_or_tsquery(:query)) AS rank
        FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.user_id = :userId
          AND d.deleted_at IS NULL
          AND d.id IN (:docIds)
          AND dc.content_tsv @@ verity_or_tsquery(:query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchIdsWithRankScoped(
        @Param("userId") UUID userId,
        @Param("query") String query,
        @Param("docIds") Collection<UUID> docIds,
        @Param("limit") int limit
    );

    // ------------------------------------------------------------------
    // Vector search (Phase 2) — embedding column managed via native SQL;
    // it is intentionally not mapped on the entity.
    // ------------------------------------------------------------------

    /** Nearest embedded chunks: rows are [chunk_id, cosine_distance]. */
    @Query(value = """
        SELECT dc.id, (dc.embedding <=> CAST(:vector AS vector)) AS distance
        FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.user_id = :userId
          AND d.deleted_at IS NULL
          AND dc.embedding IS NOT NULL
        ORDER BY distance
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> nearestByVector(
        @Param("userId") UUID userId,
        @Param("vector") String vector,
        @Param("limit") int limit
    );

    /** Document-scoped variant: rows are [chunk_id, cosine_distance]. */
    @Query(value = """
        SELECT dc.id, (dc.embedding <=> CAST(:vector AS vector)) AS distance
        FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.user_id = :userId
          AND d.deleted_at IS NULL
          AND d.id IN (:docIds)
          AND dc.embedding IS NOT NULL
        ORDER BY distance
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> nearestByVectorScoped(
        @Param("userId") UUID userId,
        @Param("docIds") Collection<UUID> docIds,
        @Param("vector") String vector,
        @Param("limit") int limit
    );

    /** Writes an embedding only if the chunk has none (idempotent, race-safe). */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value = """
        UPDATE document_chunks
        SET embedding = CAST(:vector AS vector)
        WHERE id = :id AND embedding IS NULL
        """, nativeQuery = true)
    int setEmbeddingIfAbsent(@Param("id") UUID id, @Param("vector") String vector);

    /** Chunks of one document still awaiting embedding (native * maps the entity; extra column ignored). */
    @Query(value = """
        SELECT * FROM document_chunks
        WHERE document_id = :documentId AND embedding IS NULL
        ORDER BY chunk_index
        """, nativeQuery = true)
    List<DocumentChunk> findUnembeddedByDocumentId(@Param("documentId") UUID documentId);

    /** Oldest unembedded chunks across all documents (startup backfill). */
    @Query(value = """
        SELECT * FROM document_chunks
        WHERE embedding IS NULL
        ORDER BY created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findUnembedded(@Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM document_chunks WHERE embedding IS NULL", nativeQuery = true)
    long countUnembedded();

    /** Clears one user's embeddings so they can be rebuilt (safe re-indexing). */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(value = """
        UPDATE document_chunks dc SET embedding = NULL
        FROM documents d
        WHERE dc.document_id = d.id AND d.user_id = :userId
        """, nativeQuery = true)
    int clearEmbeddingsForUser(@Param("userId") UUID userId);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    /** Chunk counts for a page of documents in one query (avoids N+1). */
    @Query("""
        SELECT c.document.id, COUNT(c) FROM DocumentChunk c
        WHERE c.document.id IN :documentIds
        GROUP BY c.document.id
        """)
    List<Object[]> countByDocumentIds(@Param("documentIds") java.util.Collection<UUID> documentIds);
}