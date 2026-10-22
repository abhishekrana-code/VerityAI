package com.verityai.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_chunks", indexes = {
    @Index(name = "idx_document_chunks_document", columnList = "document_id"),
    @Index(name = "idx_document_chunks_fts", columnList = "content_tsv")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_document_chunk_order", columnNames = {"document_id", "chunk_index"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    // Generated column in DB: content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}