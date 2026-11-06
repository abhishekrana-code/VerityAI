package com.verityai.repository;

import com.verityai.entity.DocumentTag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTagRepository extends JpaRepository<DocumentTag, DocumentTag.Key> {

    @Query("""
        SELECT dt FROM DocumentTag dt
        JOIN FETCH dt.tag t
        WHERE dt.document.id = :documentId AND t.user.id = :userId
        ORDER BY t.name
        """)
    List<DocumentTag> findByDocumentAndUser(@Param("documentId") UUID documentId,
                                            @Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM DocumentTag dt WHERE dt.document.id = :documentId AND dt.tag.id = :tagId")
    int deleteByDocumentAndTag(@Param("documentId") UUID documentId, @Param("tagId") UUID tagId);

    @Modifying
    @Query("DELETE FROM DocumentTag dt WHERE dt.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);

    boolean existsByDocumentIdAndTagId(UUID documentId, UUID tagId);

    /** (tagId, count) for every tag owned by the user, in one query. */
    @Query("""
        SELECT dt.tag.id, COUNT(dt) FROM DocumentTag dt
        JOIN dt.tag t
        WHERE t.user.id = :userId
        GROUP BY dt.tag.id
        """)
    List<Object[]> countByUser(@Param("userId") UUID userId);
}
