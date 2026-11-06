package com.verityai.repository;

import com.verityai.entity.CollectionDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionDocumentRepository extends JpaRepository<CollectionDocument, CollectionDocument.Key> {

    List<CollectionDocument> findByCollectionId(UUID collectionId);

    @Query("""
        SELECT cd FROM CollectionDocument cd
        WHERE cd.collection.id = :collectionId AND cd.collection.user.id = :userId
        """)
    List<CollectionDocument> findByCollectionAndUser(@Param("collectionId") UUID collectionId,
                                                     @Param("userId") UUID userId);

    @Modifying
    @Query("""
        DELETE FROM CollectionDocument cd
        WHERE cd.collection.id = :collectionId AND cd.document.id = :documentId
        """)
    int deleteByCollectionAndDocument(@Param("collectionId") UUID collectionId,
                                      @Param("documentId") UUID documentId);

    @Modifying
    @Query("DELETE FROM CollectionDocument cd WHERE cd.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);

    @Query("""
        SELECT cd FROM CollectionDocument cd
        WHERE cd.document.id = :documentId AND cd.collection.user.id = :userId
        """)
    List<CollectionDocument> findByDocumentAndUser(@Param("documentId") UUID documentId,
                                                   @Param("userId") UUID userId);

    /** (collectionId, count) for every collection owned by the user, in one query. */
    @Query("""
        SELECT cd.collection.id, COUNT(cd) FROM CollectionDocument cd
        JOIN cd.collection c
        WHERE c.user.id = :userId
        GROUP BY cd.collection.id
        """)
    List<Object[]> countByUser(@Param("userId") UUID userId);
}
