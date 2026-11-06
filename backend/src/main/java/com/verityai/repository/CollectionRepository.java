package com.verityai.repository;

import com.verityai.entity.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionRepository extends JpaRepository<Collection, UUID> {

    List<Collection> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<Collection> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    /** Collections containing any of the given documents (one query, no N+1). */
    @Query("""
        SELECT DISTINCT c FROM CollectionDocument cd
        JOIN cd.collection c
        WHERE c.user.id = :userId AND cd.document.id IN :documentIds
        """)
    List<Collection> findByUserIdAndDocumentIds(@Param("userId") UUID userId,
                                                @Param("documentIds") List<UUID> documentIds);

    /** Collection ids that contain the given document (for list filtering). */
    @Query("""
        SELECT c.id FROM CollectionDocument cd
        JOIN cd.collection c
        WHERE c.user.id = :userId AND cd.document.id = :documentId
        """)
    List<UUID> findCollectionIdsByDocument(@Param("userId") UUID userId,
                                           @Param("documentId") UUID documentId);

    /**
     * Membership pairs (documentId, collection) for a page of documents in one
     * query, so the list view never issues per-document lookups.
     */
    @Query("""
        SELECT cd.document.id, c FROM CollectionDocument cd
        JOIN cd.collection c
        WHERE c.user.id = :userId AND cd.document.id IN :documentIds
        ORDER BY c.updatedAt DESC
        """)
    List<Object[]> findMembershipByDocumentIds(@Param("userId") UUID userId,
                                               @Param("documentIds") List<UUID> documentIds);
}
