package com.verityai.repository;

import com.verityai.entity.MessageCitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageCitationRepository extends JpaRepository<MessageCitation, UUID> {

    List<MessageCitation> findByMessageIdOrderByCitationIndexAsc(UUID messageId);

    @Query("SELECT mc FROM MessageCitation mc WHERE mc.message.id = :messageId ORDER BY mc.citationIndex")
    List<MessageCitation> findByMessageId(UUID messageId);

    void deleteByMessageId(UUID messageId);
}