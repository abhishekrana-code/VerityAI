package com.verityai.repository;

import com.verityai.entity.KnowledgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface KnowledgeEntityRepository extends JpaRepository<KnowledgeEntity, UUID>,
        JpaSpecificationExecutor<KnowledgeEntity> {

    long countByUserId(UUID userId);

    long countByUserIdAndDocumentId(UUID userId, UUID documentId);

    @Query("SELECT COUNT(DISTINCT e.document.id) FROM KnowledgeEntity e WHERE e.user.id = :userId")
    long countDistinctDocumentsByUserId(@Param("userId") UUID userId);

    @Query("SELECT e FROM KnowledgeEntity e WHERE e.user.id = :userId AND e.document.id = :documentId " +
           "ORDER BY e.mentionCount DESC, e.name ASC")
    List<KnowledgeEntity> findByUserAndDocument(@Param("userId") UUID userId,
                                                @Param("documentId") UUID documentId);

    @Modifying
    @Transactional
    @Query("DELETE FROM KnowledgeEntity e WHERE e.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
