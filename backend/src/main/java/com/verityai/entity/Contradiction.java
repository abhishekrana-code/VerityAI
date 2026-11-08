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
 * One detected contradiction between two claims (Phase 3, Feature 8).
 *
 * <p>A row is only written when the bounded classifier accepted the pair as one
 * of the five approved classes. {@code severity} is an AI assessment of how
 * material the conflict is — never a calibrated probability. Both claims and
 * their document/page provenance are stored so the user can read the evidence
 * instead of trusting a bare label.</p>
 */
@Entity
@Table(name = "contradictions",
    indexes = {
        @Index(name = "idx_contradictions_user_detected", columnList = "user_id, detected_at"),
        @Index(name = "idx_contradictions_user_type", columnList = "user_id, contradiction_type"),
        @Index(name = "idx_contradictions_document_a", columnList = "document_a_id"),
        @Index(name = "idx_contradictions_document_b", columnList = "document_b_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_contradiction_pair", columnNames = {"user_id", "pair_key"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Contradiction {

    public enum ContradictionType {
        NUMERIC_CONFLICT,
        TEMPORAL_CONFLICT,
        ATTRIBUTE_CONFLICT,
        CAUSAL_CONFLICT,
        POLARITY_CONFLICT
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_a_id", nullable = false)
    private Document documentA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_b_id", nullable = false)
    private Document documentB;

    /** Chunk that produced claim A; nullable so chunk deletion never removes the finding. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_a_id")
    private DocumentChunk chunkA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_b_id")
    private DocumentChunk chunkB;

    @Column(name = "claim_a", nullable = false, columnDefinition = "TEXT")
    private String claimA;

    @Column(name = "claim_b", nullable = false, columnDefinition = "TEXT")
    private String claimB;

    @Column(name = "page_a")
    private Integer pageA;

    @Column(name = "page_b")
    private Integer pageB;

    /** Shared subject entity that made the pair comparable. */
    @Column(name = "subject_entity", length = 255)
    private String subjectEntity;

    @Enumerated(EnumType.STRING)
    @Column(name = "contradiction_type", nullable = false, length = 40)
    private ContradictionType contradictionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private Severity severity;

    @Column(name = "explanation", length = 600)
    private String explanation;

    @Column(name = "model", length = 120)
    private String model;

    /** SHA-256 of the canonically ordered pair; prevents duplicate rows per detection. */
    @Column(name = "pair_key", nullable = false, length = 64)
    private String pairKey;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
