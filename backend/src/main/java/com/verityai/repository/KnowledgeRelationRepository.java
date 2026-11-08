package com.verityai.repository;

import com.verityai.entity.KnowledgeRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface KnowledgeRelationRepository extends JpaRepository<KnowledgeRelation, UUID>,
        JpaSpecificationExecutor<KnowledgeRelation> {

    long countByUserId(UUID userId);

    long countByUserIdAndDocumentId(UUID userId, UUID documentId);

    @Modifying
    @Transactional
    @Query("DELETE FROM KnowledgeRelation r WHERE r.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
