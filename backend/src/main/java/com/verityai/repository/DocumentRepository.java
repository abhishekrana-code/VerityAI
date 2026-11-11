package com.verityai.repository;

import com.verityai.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    Page<Document> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Document> findByUserIdAndStatusAndDeletedAtIsNull(UUID userId, Document.DocumentStatus status, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.user.id = :userId AND d.deletedAt IS NULL AND " +
           "(LOWER(d.originalFilename) LIKE %:query% OR d.sha256 = :query) ORDER BY d.createdAt DESC")
    Page<Document> searchByUserId(@Param("userId") UUID userId, @Param("query") String query, Pageable pageable);

    Optional<Document> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<Document> findByUserIdAndSha256AndDeletedAtIsNull(UUID userId, String sha256);

    List<Document> findByUserIdAndStatusInAndDeletedAtIsNull(UUID userId, List<Document.DocumentStatus> statuses);

    List<Document> findByStatusInAndDeletedAtIsNull(List<Document.DocumentStatus> statuses);

    long countByUserIdAndDeletedAtIsNull(UUID userId);

    @Query("SELECT COUNT(d) FROM Document d WHERE d.user.id = :userId AND d.deletedAt IS NULL")
    long countByUserId(@Param("userId") UUID userId);

    /**
     * Status-only update for the asynchronous processing thread. It never
     * touches {@code deletedAt}, so a document deleted while it was being
     * processed cannot be resurrected by a stale entity merge. Returns 0 when
     * the row no longer exists or was soft-deleted.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.status = :status WHERE d.id = :id AND d.deletedAt IS NULL")
    int updateStatusIfNotDeleted(@Param("id") UUID id, @Param("status") Document.DocumentStatus status);

    /** Same guarantee as {@link #updateStatusIfNotDeleted}, plus page count. */
    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.status = :status, d.pageCount = :pageCount " +
           "WHERE d.id = :id AND d.deletedAt IS NULL")
    int updateProcessingResult(@Param("id") UUID id,
                               @Param("status") Document.DocumentStatus status,
                               @Param("pageCount") Integer pageCount);

    // ------------------------------------------------------------------
    // Phase 3: knowledge graph lifecycle
    // ------------------------------------------------------------------

    @Query("""
        SELECT d.id FROM Document d
        WHERE d.status = :status
        AND d.deletedAt IS NULL
        AND d.knowledgeStatus IN :statuses
        AND EXISTS (SELECT 1 FROM DocumentChunk c WHERE c.document.id = d.id)
        """)
    List<UUID> findDocumentsPendingKnowledge(
        @Param("status") Document.DocumentStatus status,
        @Param("statuses") java.util.Collection<Document.KnowledgeStatus> statuses);

    @Query("""
        SELECT d.id FROM Document d
        WHERE d.user.id = :userId
        AND d.status = :status
        AND d.deletedAt IS NULL
        AND EXISTS (SELECT 1 FROM DocumentChunk c WHERE c.document.id = d.id)
        ORDER BY d.updatedAt DESC
        """)
    List<UUID> findReadyDocumentIdsForUser(@Param("userId") UUID userId,
                                           @Param("status") Document.DocumentStatus status,
                                           Pageable pageable);

    @Query("SELECT MAX(d.knowledgeGeneratedAt) FROM Document d " +
           "WHERE d.user.id = :userId AND d.deletedAt IS NULL")
    java.time.Instant findLatestKnowledgeGeneratedAtForUser(@Param("userId") UUID userId);

    long countByUserIdAndKnowledgeStatusAndDeletedAtIsNull(UUID userId, Document.KnowledgeStatus status);
}