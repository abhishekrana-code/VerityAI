package com.verityai.repository;

import com.verityai.entity.Contradiction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContradictionRepository extends JpaRepository<Contradiction, UUID>,
        JpaSpecificationExecutor<Contradiction> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Contradiction c WHERE c.user.id = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Removes findings that involve one document. Detection is scoped per
     * document, so re-running it replaces every pair that mentions that
     * document while leaving unrelated pairs intact.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Contradiction c WHERE c.user.id = :userId "
        + "AND (c.documentA.id = :documentId OR c.documentB.id = :documentId)")
    int deleteByUserIdAndDocument(@Param("userId") UUID userId,
                                  @Param("documentId") UUID documentId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Contradiction c "
        + "WHERE c.documentA.id = :documentId OR c.documentB.id = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    @Query("SELECT c.contradictionType, COUNT(c) FROM Contradiction c "
        + "WHERE c.user.id = :userId GROUP BY c.contradictionType")
    List<Object[]> countByType(@Param("userId") UUID userId);

    @Query("SELECT c.severity, COUNT(c) FROM Contradiction c "
        + "WHERE c.user.id = :userId GROUP BY c.severity")
    List<Object[]> countBySeverity(@Param("userId") UUID userId);

    @Query("SELECT MAX(c.detectedAt) FROM Contradiction c WHERE c.user.id = :userId")
    Instant findLatestDetectedAt(@Param("userId") UUID userId);
}
