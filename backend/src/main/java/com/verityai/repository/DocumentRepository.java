package com.verityai.repository;

import com.verityai.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Document> findByUserIdAndStatusAndDeletedAtIsNull(UUID userId, Document.DocumentStatus status, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.user.id = :userId AND d.deletedAt IS NULL AND " +
           "(LOWER(d.originalFilename) LIKE %:query% OR d.sha256 = :query) ORDER BY d.createdAt DESC")
    Page<Document> searchByUserId(@Param("userId") UUID userId, @Param("query") String query, Pageable pageable);

    Optional<Document> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<Document> findBySha256AndDeletedAtIsNull(String sha256);

    List<Document> findByUserIdAndStatusInAndDeletedAtIsNull(UUID userId, List<Document.DocumentStatus> statuses);

    long countByUserIdAndDeletedAtIsNull(UUID userId);

    @Query("SELECT COUNT(d) FROM Document d WHERE d.user.id = :userId AND d.deletedAt IS NULL")
    long countByUserId(@Param("userId") UUID userId);
}