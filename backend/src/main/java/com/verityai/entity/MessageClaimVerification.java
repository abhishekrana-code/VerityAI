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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One analyzed claim of an assistant message, assessed against its cited
 * evidence excerpts (Phase 2, Feature 6).
 *
 * <p>{@link SupportClass} is an AI assessment of grounding in the cited
 * excerpts — never a calibrated probability. {@code null} means the claim was
 * recorded but not classified (partial verification); such rows keep their
 * citation data so a later re-verification can finish the job.</p>
 */
@Entity
@Table(name = "message_claim_verifications", indexes = {
    @Index(name = "idx_message_claim_verifications_message", columnList = "message_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_message_claim", columnNames = {"message_id", "claim_index"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MessageClaimVerification {

    public enum SupportClass {
        SUPPORTED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING
    }

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "claim_index", nullable = false)
    private int claimIndex;

    @Column(name = "sentence_index", nullable = false)
    private int sentenceIndex;

    @Column(name = "claim_text", nullable = false, columnDefinition = "TEXT")
    private String claimText;

    /** null = analyzed but not classified (model omitted it or model call failed). */
    @Enumerated(EnumType.STRING)
    @Column(name = "support_class", length = 32)
    private SupportClass supportClass;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    /** 0-based citation indexes ([Sn] references) this claim relies on. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citation_indexes", nullable = false, columnDefinition = "jsonb")
    private List<Integer> citationIndexes = new ArrayList<>();

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt = Instant.now();
}
