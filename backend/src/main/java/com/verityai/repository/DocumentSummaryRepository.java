package com.verityai.repository;

import com.verityai.entity.DocumentSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentSummaryRepository extends JpaRepository<DocumentSummary, UUID> {

    List<DocumentSummary> findByDocumentIdOrderByKind(@Param("documentId") UUID documentId);

    Optional<DocumentSummary> findByDocumentIdAndKind(UUID documentId, DocumentSummary.Kind kind);

    /** Summary sections for a page of documents in one query (no N+1). */
    @Query("SELECT s FROM DocumentSummary s JOIN FETCH s.document WHERE s.document.id IN (:ids)")
    List<DocumentSummary> findByDocumentIdIn(@Param("ids") java.util.Collection<UUID> ids);

    @Modifying
    @Transactional
    @Query("DELETE FROM DocumentSummary s WHERE s.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);

    /** Documents that are READY but have no completed summaries (includes
     *  PENDING rows stranded by a restart mid-generation). */
    @Query("""
        SELECT d.id FROM Document d
        WHERE d.status = 'READY' AND d.deletedAt IS NULL
        AND d.summaryStatus IN ('NONE', 'PENDING', 'FAILED')
        AND EXISTS (SELECT 1 FROM DocumentChunk c WHERE c.document.id = d.id)
        """)
    List<UUID> findDocumentsPendingSummary();
}
