package com.verityai.repository;

import com.verityai.entity.DocumentProcessingEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentProcessingEventRepository extends JpaRepository<DocumentProcessingEvent, UUID> {

    @Query("""
        SELECT e FROM DocumentProcessingEvent e
        WHERE e.document.id = :documentId
        ORDER BY e.createdAt DESC, e.attempt DESC
        """)
    List<DocumentProcessingEvent> findByDocumentIdOrderByCreatedAtDesc(@Param("documentId") UUID documentId);

    @Query("""
        SELECT COALESCE(MAX(e.attempt), 0) FROM DocumentProcessingEvent e
        WHERE e.document.id = :documentId
        """)
    int findMaxAttempt(@Param("documentId") UUID documentId);

    long countByDocumentIdAndStage(UUID documentId, DocumentProcessingEvent.Stage stage);

    /** Latest event per document for a page of documents (single query, no N+1). */
    @Query("""
        SELECT e FROM DocumentProcessingEvent e
        WHERE e.document.id IN :documentIds
        AND e.createdAt = (
            SELECT MAX(e2.createdAt) FROM DocumentProcessingEvent e2
            WHERE e2.document.id = e.document.id
        )
        """)
    List<DocumentProcessingEvent> findLatestByDocumentIds(@Param("documentIds") List<UUID> documentIds);

    /** Processing history across the user's documents, newest first. */
    @Query(value = """
        SELECT e FROM DocumentProcessingEvent e
        JOIN FETCH e.document d
        WHERE d.user.id = :userId AND d.deletedAt IS NULL
        ORDER BY e.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(e) FROM DocumentProcessingEvent e
        JOIN e.document d
        WHERE d.user.id = :userId AND d.deletedAt IS NULL
        """)
    org.springframework.data.domain.Page<DocumentProcessingEvent> findForUser(
        @Param("userId") UUID userId, org.springframework.data.domain.Pageable pageable);
}
