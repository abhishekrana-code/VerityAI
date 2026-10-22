package com.verityai.repository;

import com.verityai.entity.ConversationDocument;
import com.verityai.entity.ConversationDocumentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationDocumentRepository extends JpaRepository<ConversationDocument, ConversationDocumentId> {

    List<ConversationDocument> findByConversationId(UUID conversationId);

    List<ConversationDocument> findByDocumentId(UUID documentId);

    boolean existsByConversationIdAndDocumentId(UUID conversationId, UUID documentId);

    void deleteByConversationId(UUID conversationId);

    void deleteByDocumentId(UUID documentId);
}