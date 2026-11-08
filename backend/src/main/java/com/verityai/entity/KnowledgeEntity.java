package com.verityai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One entity mentioned by one document (Phase 3, Feature 7).
 *
 * <p>Stored in PostgreSQL only. Entities come from deterministic pattern
 * extraction over the document chunks, merged with the LLM-extracted ENTITIES
 * summary section when it exists. {@code evidenceExcerpt} is the passage where
 * the entity was first seen, so the UI can show provenance instead of asking
 * the user to trust a bare node.</p>
 */
@Entity
@Table(name = "knowledge_entities",
    indexes = {
        @Index(name = "idx_knowledge_entities_user", columnList = "user_id"),
        @Index(name = "idx_knowledge_entities_document", columnList = "document_id"),
        @Index(name = "idx_knowledge_entities_user_name", columnList = "user_id, normalized_name")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_knowledge_entity_document",
            columnNames = {"document_id", "normalized_name", "entity_type"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntity {

    public enum EntityType {
        PERSON, ORGANIZATION, LOCATION, PRODUCT, EVENT,
        DATE, METRIC, ACRONYM, CONCEPT, OTHER
    }

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    /** Entity text as written in the document. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Lower-cased, punctuation-normalized name used for de-duplication. */
    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType;

    @Column(name = "mention_count", nullable = false)
    private int mentionCount = 1;

    @Column(name = "first_page")
    private Integer firstPage;

    @Column(name = "evidence_excerpt", length = 500)
    private String evidenceExcerpt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
