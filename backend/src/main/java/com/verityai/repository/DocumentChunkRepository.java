package com.verityai.repository;

import com.verityai.entity.DocumentChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    Page<DocumentChunk> findByDocumentId(UUID documentId, Pageable pageable);

    @Query(value = """
        SELECT dc.* FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.user_id = :userId
          AND d.deleted_at IS NULL
          AND dc.content_tsv @@ websearch_to_tsquery('english', :query)
        ORDER BY ts_rank_cd(dc.content_tsv, websearch_to_tsquery('english', :query)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> fullTextSearch(@Param("userId") UUID userId, @Param("query") String query, @Param("limit") int limit);

    @Query(value = """
        SELECT dc.*, ts_rank_cd(dc.content_tsv, websearch_to_tsquery('english', :query)) as rank
        FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.user_id = :userId
          AND d.deleted_at IS NULL
          AND dc.content_tsv @@ websearch_to_tsquery('english', :query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> fullTextSearchWithRank(@Param("userId") UUID userId, @Param("query") String query, @Param("limit") int limit);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}