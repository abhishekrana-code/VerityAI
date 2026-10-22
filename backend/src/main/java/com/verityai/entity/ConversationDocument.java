package com.verityai.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_documents")
@IdClass(ConversationDocumentId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDocument {

    @Id
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Id
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @CreationTimestamp
    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", insertable = false, updatable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", insertable = false, updatable = false)
    private Document document;
}