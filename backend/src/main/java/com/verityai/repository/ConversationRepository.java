package com.verityai.repository;

import com.verityai.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Page<Conversation> findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID userId, Pageable pageable);

    Page<Conversation> findByUserIdAndTitleContainingIgnoreCaseAndArchivedAtIsNull(
            UUID userId, String title, Pageable pageable);

    Optional<Conversation> findByIdAndUserIdAndArchivedAtIsNull(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE conversations SET updated_at = :now WHERE id = :id", nativeQuery = true)
    void touch(@Param("id") UUID id, @Param("now") Instant now);

    List<Conversation> findByUserIdAndArchivedAtIsNotNull(UUID userId);
}