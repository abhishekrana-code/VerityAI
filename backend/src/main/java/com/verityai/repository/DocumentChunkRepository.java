package com.verityai.repository;

import com.verityai.entity.DocumentChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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