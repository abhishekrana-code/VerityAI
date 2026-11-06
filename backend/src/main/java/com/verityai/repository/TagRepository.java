package com.verityai.repository;

import com.verityai.entity.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findByUserIdOrderByNameAsc(UUID userId);

    Optional<Tag> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    /** Tags for a page of documents in one query (avoids N+1 on the list view). */
    @Query("""
        SELECT t, dt.document.id FROM DocumentTag dt
        JOIN dt.tag t
        WHERE dt.document.id IN :documentIds AND t.user.id = :userId
        ORDER BY t.name
        """)
    List<Object[]> findTagsByDocumentIds(@Param("userId") UUID userId, @Param("documentIds") List<UUID> documentIds);
}
