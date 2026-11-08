package com.verityai.repository;

import com.verityai.entity.MessageClaimVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageClaimVerificationRepository extends JpaRepository<MessageClaimVerification, UUID> {

    List<MessageClaimVerification> findByMessageIdOrderByClaimIndexAsc(UUID messageId);

    @Query("SELECT mcv FROM MessageClaimVerification mcv JOIN FETCH mcv.message WHERE mcv.message.id = :messageId ORDER BY mcv.claimIndex")
    List<MessageClaimVerification> findByMessageIdWithMessage(@Param("messageId") UUID messageId);

    /** Verification rows for a page of messages in one query (no N+1). */
    @Query("SELECT mcv FROM MessageClaimVerification mcv JOIN FETCH mcv.message " +
           "WHERE mcv.message.id IN (:ids) ORDER BY mcv.message.id, mcv.claimIndex")
    List<MessageClaimVerification> findByMessageIdIn(@Param("ids") java.util.Collection<UUID> ids);

    @Modifying
    @Query("DELETE FROM MessageClaimVerification mcv WHERE mcv.message.id = :messageId")
    void deleteByMessageId(@Param("messageId") UUID messageId);
}
