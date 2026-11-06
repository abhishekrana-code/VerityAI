package com.verityai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Join table: collections ↔ documents. Composite key (collection_id, document_id).
 *
 * <p>The {@code *Id} fields are the actual {@code @Id} properties (matching
 * {@link Key}); the associations are read-only views over the same columns so
 * JPQL can still navigate {@code cd.collection} / {@code cd.document}.</p>
 */
@Entity
@Table(name = "collection_documents")
@IdClass(CollectionDocument.Key.class)
public class CollectionDocument {

    public static class Key implements Serializable {
        private UUID collectionId;
        private UUID documentId;

        public Key() {
        }

        public Key(UUID collectionId, UUID documentId) {
            this.collectionId = collectionId;
            this.documentId = documentId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(collectionId, key.collectionId) && Objects.equals(documentId, key.documentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(collectionId, documentId);
        }
    }

    @Id
    @Column(name = "collection_id")
    private UUID collectionId;

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", insertable = false, updatable = false)
    private Collection collection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", insertable = false, updatable = false)
    private Document document;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }

    public UUID getCollectionId() {
        return collectionId;
    }

    public void setCollectionId(UUID collectionId) {
        this.collectionId = collectionId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public Collection getCollection() {
        return collection;
    }

    public void setCollection(Collection collection) {
        this.collection = collection;
        this.collectionId = collection != null ? collection.getId() : null;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
        this.documentId = document != null ? document.getId() : null;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Instant addedAt) {
        this.addedAt = addedAt;
    }
}
