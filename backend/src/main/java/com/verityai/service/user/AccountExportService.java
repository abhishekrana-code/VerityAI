package com.verityai.service.user;

import com.verityai.dto.response.AccountExportResponse;
import com.verityai.entity.Contradiction;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.MessageClaimVerification;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.ContradictionRepository;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageClaimVerificationRepository;
import com.verityai.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the signed-in user's own rows back as one JSON document. Deleted
 * documents are omitted, and every query is scoped by user id, so an export
 * can never contain another account's data.
 */
@Service
@RequiredArgsConstructor
public class AccountExportService {

    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageCitationRepository messageCitationRepository;
    private final MessageClaimVerificationRepository claimVerificationRepository;
    private final ContradictionRepository contradictionRepository;

    @Transactional(readOnly = true)
    public AccountExportResponse export(User user) {
        if (user == null) {
            throw ApiException.unauthenticated("Authentication required");
        }
        UUID userId = user.getId();

        AccountExportResponse export = new AccountExportResponse();
        export.setExportedAt(Instant.now());
        export.setAccount(account(user));
        export.setDocuments(documents(userId));

        List<Message> messages = messageRepository.findByConversationUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, List<MessageCitation>> citations = citations(messages);
        Map<UUID, List<MessageClaimVerification>> claims = claims(messages);

        export.setConversations(conversations(userId, messages, citations));
        export.setChecks(checks(claims, userId));
        return export;
    }

    private AccountExportResponse.Account account(User user) {
        AccountExportResponse.Account account = new AccountExportResponse.Account();
        account.setId(user.getId());
        account.setEmail(user.getEmail());
        account.setDisplayName(user.getDisplayName());
        account.setCreatedAt(user.getCreatedAt());
        return account;
    }

    private List<AccountExportResponse.DocumentEntry> documents(UUID userId) {
        return documentRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId).stream()
            .map(this::document)
            .toList();
    }

    private AccountExportResponse.DocumentEntry document(Document document) {
        AccountExportResponse.DocumentEntry entry = new AccountExportResponse.DocumentEntry();
        entry.setId(document.getId());
        entry.setFilename(document.getOriginalFilename());
        entry.setSizeBytes(document.getSizeBytes());
        entry.setPageCount(document.getPageCount());
        entry.setStatus(document.getStatus().name());
        entry.setCreatedAt(document.getCreatedAt());
        entry.setUpdatedAt(document.getUpdatedAt());
        return entry;
    }

    private Map<UUID, List<MessageCitation>> citations(List<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        List<UUID> messageIds = messages.stream().map(Message::getId).toList();
        return messageCitationRepository.findByMessageIdInWithDocument(messageIds).stream()
            .collect(Collectors.groupingBy(
                citation -> citation.getMessage().getId(),
                Collectors.mapping(Function.identity(), Collectors.toList())));
    }

    private Map<UUID, List<MessageClaimVerification>> claims(List<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        List<UUID> messageIds = messages.stream().map(Message::getId).toList();
        return claimVerificationRepository.findByMessageIdIn(messageIds).stream()
            .collect(Collectors.groupingBy(check -> check.getMessage().getId()));
    }

    private List<AccountExportResponse.ConversationEntry> conversations(
            UUID userId,
            List<Message> messages,
            Map<UUID, List<MessageCitation>> citations) {
        Map<UUID, List<Message>> byConversation = messages.stream()
            .collect(Collectors.groupingBy(
                message -> message.getConversation().getId(),
                LinkedHashMap::new,
                Collectors.toList()));

        return conversationRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
            .map(conversation -> {
                AccountExportResponse.ConversationEntry entry = new AccountExportResponse.ConversationEntry();
                entry.setId(conversation.getId());
                entry.setTitle(conversation.getTitle());
                entry.setCreatedAt(conversation.getCreatedAt());
                entry.setUpdatedAt(conversation.getUpdatedAt());
                entry.setArchivedAt(conversation.getArchivedAt());
                entry.setMessages(byConversation.getOrDefault(conversation.getId(), List.of()).stream()
                    .map(message -> message(message, citations.getOrDefault(message.getId(), List.of())))
                    .toList());
                return entry;
            })
            .toList();
    }

    private AccountExportResponse.MessageEntry message(Message message, List<MessageCitation> citations) {
        AccountExportResponse.MessageEntry entry = new AccountExportResponse.MessageEntry();
        entry.setId(message.getId());
        entry.setRole(message.getRole().name());
        entry.setContent(message.getContent());
        entry.setStatus(message.getStatus().name());
        entry.setModel(message.getModel());
        entry.setEvidenceStatus(message.getEvidenceStatus() == null ? null : message.getEvidenceStatus().name());
        entry.setCreatedAt(message.getCreatedAt());
        entry.setCitations(citations.stream().map(this::citation).toList());
        return entry;
    }

    private AccountExportResponse.CitationEntry citation(MessageCitation citation) {
        AccountExportResponse.CitationEntry entry = new AccountExportResponse.CitationEntry();
        entry.setDocumentId(citation.getDocument().getId());
        entry.setDocumentFilename(citation.getDocument().getOriginalFilename());
        entry.setPageNumber(citation.getPageNumber());
        entry.setExcerpt(citation.getExcerpt());
        entry.setCitationIndex(citation.getCitationIndex());
        return entry;
    }

    private AccountExportResponse.Checks checks(
            Map<UUID, List<MessageClaimVerification>> claims,
            UUID userId) {
        AccountExportResponse.Checks checks = new AccountExportResponse.Checks();

        List<AccountExportResponse.ClaimCheck> claimChecks = new ArrayList<>();
        claims.values().forEach(perMessage -> perMessage.forEach(verification -> {
            AccountExportResponse.ClaimCheck entry = new AccountExportResponse.ClaimCheck();
            entry.setMessageId(verification.getMessage().getId());
            entry.setClaimIndex(verification.getClaimIndex());
            entry.setClaim(verification.getClaimText());
            entry.setSupportClass(verification.getSupportClass() == null
                ? null : verification.getSupportClass().name());
            entry.setExplanation(verification.getExplanation());
            entry.setVerifiedAt(verification.getVerifiedAt());
            claimChecks.add(entry);
        }));
        checks.setClaims(claimChecks);
        checks.setContradictions(contradictionRepository.findByUserIdOrderByDetectedAtDesc(userId).stream()
            .map(this::contradiction)
            .toList());
        return checks;
    }

    private AccountExportResponse.ContradictionEntry contradiction(Contradiction contradiction) {
        AccountExportResponse.ContradictionEntry entry = new AccountExportResponse.ContradictionEntry();
        entry.setId(contradiction.getId());
        entry.setDocumentA(contradiction.getDocumentA().getOriginalFilename());
        entry.setDocumentB(contradiction.getDocumentB().getOriginalFilename());
        entry.setClaimA(contradiction.getClaimA());
        entry.setClaimB(contradiction.getClaimB());
        entry.setPageA(contradiction.getPageA());
        entry.setPageB(contradiction.getPageB());
        entry.setType(contradiction.getContradictionType().name());
        entry.setSeverity(contradiction.getSeverity().name());
        entry.setExplanation(contradiction.getExplanation());
        entry.setDetectedAt(contradiction.getDetectedAt());
        return entry;
    }
}
