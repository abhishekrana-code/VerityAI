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
 * A link between two entities that occur in the same document chunk.
 *
 * <p>{@link RelationType#CO_OCCURS} is deliberately the only relation type for
 * now: the graph does not claim that two entities are semantically related,
 * only that they were mentioned together in one passage. {@code weight} is the
 * number of passages in which the pair co-occurs.</p>
 */
@Entity
@Table(name = "knowledge_relations",
    indexes = {
        @Index(name = "idx_knowledge_relations_user", columnList = "user_id"),
        @Index(name = "idx_knowledge_relations_document", columnList = "document_id"),
        @Index(name = "idx_knowledge_relations_source", columnList = "source_entity_id"),
        @Index(name = "idx_knowledge_relations_target", columnList = "target_entity_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_knowledge_relation",
            columnNames = {"document_id", "source_entity_id", "target_entity_id", "relation_type"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRelation {

    public enum RelationType {
        /** Two entities were mentioned in the same passage. */
        CO_OCCURS
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

    /** Canonical ordering is enforced in the database (source < target). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_entity_id", nullable = false)
    private KnowledgeEntity sourceEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_entity_id", nullable = false)
    private KnowledgeEntity targetEntity;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 40)
    private RelationType relationType = RelationType.CO_OCCURS;

    /** Number of passages in which the pair co-occurs. */
    @Column(name = "weight", nullable = false)
    private int weight = 1;

    @Column(name = "first_page")
    private Integer firstPage;

    @Column(name = "evidence_excerpt", length = 500)
    private String evidenceExcerpt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
