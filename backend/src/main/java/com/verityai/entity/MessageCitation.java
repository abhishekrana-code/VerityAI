package com.verityai.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "message_citations", indexes = {
    @Index(name = "idx_message_citations_message", columnList = "message_id"),
    @Index(name = "idx_message_citations_chunk", columnList = "chunk_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_message_citation_index", columnNames = {"message_id", "citation_index"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MessageCitation {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id", nullable = false)
    private DocumentChunk chunk;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "excerpt", nullable = false, columnDefinition = "TEXT")
    private String excerpt;

    @Column(name = "rank", nullable = false)
    private Float rank;

    @Column(name = "citation_index", nullable = false)
    private Integer citationIndex;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}