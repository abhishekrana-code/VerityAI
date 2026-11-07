package com.verityai.controller;

import com.verityai.dto.request.AskQuestionRequest;
import com.verityai.dto.request.CreateConversationRequest;
import com.verityai.dto.request.UpdateConversationRequest;
import com.verityai.dto.response.ClaimVerificationResponse;
import com.verityai.dto.response.ConversationListResponse;
import com.verityai.dto.response.ConversationResponse;
import com.verityai.dto.response.MessageListResponse;
import com.verityai.dto.response.MessageResponse;
import com.verityai.entity.Conversation;
import com.verityai.entity.ConversationDocument;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.security.CurrentUser;
import com.verityai.security.RateLimitService;
import com.verityai.service.ai.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final MessageCitationRepository citationRepository;
    private final ChatService chatService;
    private final RateLimitService rateLimitService;
    private final com.verityai.service.verification.ClaimVerificationService claimVerificationService;

    @PostMapping
    public ResponseEntity<ConversationResponse> create(@CurrentUser User user, @Valid @RequestBody CreateConversationRequest request) {
        Conversation conversation = new Conversation();
        conversation.setUser(user);
        conversation.setTitle(request.getTitle());
        conversationRepository.save(conversation);

        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            List<Document> docs = documentRepository.findAllById(request.getDocumentIds());
            docs.forEach(doc -> {
                if (!doc.getUser().getId().equals(user.getId())) {
                    throw ApiException.forbidden("Document access denied: " + doc.getId());
                }
            });
            Set<ConversationDocument> docSet = new HashSet<>();
            for (Document doc : docs) {
                ConversationDocument cd = new ConversationDocument();
                cd.setConversationId(conversation.getId());
                cd.setDocumentId(doc.getId());
                cd.setConversation(conversation);
                cd.setDocument(doc);
                docSet.add(cd);
            }
            conversation.setDocuments(docSet);
            conversationRepository.save(conversation);
        }

        return ResponseEntity.ok(toResponse(conversation));
    }

    @GetMapping
    public ResponseEntity<ConversationListResponse> list(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String q
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));

        Page<Conversation> conversations;
        if (q != null && !q.isBlank()) {
            conversations = conversationRepository.findByUserIdAndTitleContainingIgnoreCaseAndArchivedAtIsNull(user.getId(), q, pageable);
        } else {
            conversations = conversationRepository.findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(user.getId(), pageable);
        }

        ConversationListResponse resp = new ConversationListResponse();
        resp.setContent(conversations.map(this::toResponse).getContent());
        resp.setPage(conversations.getNumber());
        resp.setSize(conversations.getSize());
        resp.setTotalElements(conversations.getTotalElements());
        resp.setTotalPages(conversations.getTotalPages());
        resp.setFirst(conversations.isFirst());
        resp.setLast(conversations.isLast());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> get(@CurrentUser User user, @PathVariable UUID id) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        return ResponseEntity.ok(toResponse(conversation));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ConversationResponse> update(@CurrentUser User user, @PathVariable UUID id, @Valid @RequestBody UpdateConversationRequest request) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));

        if (request.getTitle() != null) {
            conversation.setTitle(request.getTitle());
        }
        if (request.getArchived() != null && request.getArchived()) {
            conversation.setArchivedAt(java.time.Instant.now());
        }
        if (request.getDocumentIds() != null) {
            conversation.getDocuments().clear();
            if (!request.getDocumentIds().isEmpty()) {
                List<Document> docs = documentRepository.findAllById(request.getDocumentIds());
                for (Document doc : docs) {
                    if (!doc.getUser().getId().equals(user.getId())) {
                        throw ApiException.forbidden("Document access denied: " + doc.getId());
                    }
                    ConversationDocument cd = new ConversationDocument();
                    cd.setConversationId(conversation.getId());
                    cd.setDocumentId(doc.getId());
                    cd.setConversation(conversation);
                    cd.setDocument(doc);
                    conversation.getDocuments().add(cd);
                }
            }
        }

        conversationRepository.touch(id, java.time.Instant.now());
        conversationRepository.save(conversation);
        return ResponseEntity.ok(toResponse(conversation));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        conversationRepository.delete(conversation);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<MessageListResponse> getMessages(
            @CurrentUser User user,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
    ) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(id, pageable);

        List<UUID> messageIds = messages.getContent().stream().map(Message::getId).toList();
        java.util.Map<UUID, List<MessageCitation>> citationsByMessage = messageIds.isEmpty()
            ? java.util.Map.of()
            : citationRepository.findByMessageIdInWithDocument(messageIds).stream()
                .collect(Collectors.groupingBy(c -> c.getMessage().getId()));

        MessageListResponse resp = new MessageListResponse();
        resp.setContent(messages.map(m -> toMessageResponse(m, citationsByMessage.getOrDefault(m.getId(), List.of()))).getContent());
        resp.setPage(messages.getNumber());
        resp.setSize(messages.getSize());
        resp.setTotalElements(messages.getTotalElements());
        resp.setTotalPages(messages.getTotalPages());
        resp.setFirst(messages.isFirst());
        resp.setLast(messages.isLast());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageResponse> ask(@CurrentUser User user, @PathVariable UUID id, @Valid @RequestBody AskQuestionRequest request) {
        rateLimitService.checkAiRequest(user.getId());
        Conversation conversation = conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));

        MessageResponse response = chatService.ask(conversation, request.getQuestion(), request.getDocumentIds());
        return ResponseEntity.ok(response);
    }

    /** Stored claim verification for one assistant message (no model call). */
    @GetMapping("/{id}/messages/{messageId}/verify")
    public ResponseEntity<ClaimVerificationResponse> getVerification(
            @CurrentUser User user,
            @PathVariable UUID id,
            @PathVariable UUID messageId) {
        conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        return ResponseEntity.ok(claimVerificationService.getVerification(id, messageId));
    }

    /** Runs claim verification (bounded model call, rate-limited) and stores the result. */
    @PostMapping("/{id}/messages/{messageId}/verify")
    public ResponseEntity<ClaimVerificationResponse> verify(
            @CurrentUser User user,
            @PathVariable UUID id,
            @PathVariable UUID messageId) {
        rateLimitService.checkAiRequest(user.getId());
        conversationRepository.findByIdAndUserIdAndArchivedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        return ResponseEntity.ok(claimVerificationService.verify(id, messageId));
    }

    private ConversationResponse toResponse(Conversation conversation) {
        ConversationResponse resp = new ConversationResponse();
        resp.setId(conversation.getId());
        resp.setTitle(conversation.getTitle());
        resp.setCreatedAt(conversation.getCreatedAt());
        resp.setUpdatedAt(conversation.getUpdatedAt());
        resp.setArchivedAt(conversation.getArchivedAt());
        resp.setMessageCount((int) messageRepository.countByConversationId(conversation.getId()));

        if (conversation.getDocuments() != null) {
            resp.setDocuments(conversation.getDocuments().stream().map(cd -> {
                ConversationResponse.DocumentSummary summary = new ConversationResponse.DocumentSummary();
                summary.setId(cd.getDocumentId());
                if (cd.getDocument() != null) {
                    summary.setOriginalFilename(cd.getDocument().getOriginalFilename());
                }
                return summary;
            }).collect(Collectors.toList()));
        } else {
            resp.setDocuments(List.of());
        }

        return resp;
    }

    private MessageResponse toMessageResponse(Message message, List<MessageCitation> citations) {
        MessageResponse resp = new MessageResponse();
        resp.setId(message.getId());
        resp.setRole(MessageResponse.MessageRole.valueOf(message.getRole().name()));
        resp.setContent(message.getContent());
        resp.setStatus(MessageResponse.MessageStatus.valueOf(message.getStatus().name()));
        resp.setModel(message.getModel());
        resp.setPromptTokens(message.getPromptTokens());
        resp.setCompletionTokens(message.getCompletionTokens());
        resp.setLatencyMs(message.getLatencyMs());
        if (message.getEvidenceStatus() != null) {
            resp.setEvidenceStatus(MessageResponse.EvidenceStatus.valueOf(message.getEvidenceStatus().name()));
        }
        resp.setCreatedAt(message.getCreatedAt());
        resp.setCitations(citations.stream().map(c -> {
            MessageResponse.CitationResponse cr = new MessageResponse.CitationResponse();
            cr.setChunkId(c.getChunk().getId());
            cr.setDocumentId(c.getDocument().getId());
            cr.setDocumentName(c.getDocument().getOriginalFilename());
            cr.setPageNumber(c.getPageNumber());
            cr.setExcerpt(c.getExcerpt());
            cr.setRank(c.getRank());
            cr.setCitationIndex(c.getCitationIndex());
            return cr;
        }).collect(Collectors.toList()));
        return resp;
    }
}