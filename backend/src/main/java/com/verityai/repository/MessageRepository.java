package com.verityai.repository;

import com.verityai.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    Page<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId, Pageable pageable);

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    long countByConversationId(UUID conversationId);
}