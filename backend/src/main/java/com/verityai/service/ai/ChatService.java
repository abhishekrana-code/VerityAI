package com.verityai.service.ai;

import com.verityai.dto.response.MessageResponse;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.exception.ErrorCode;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.service.retrieval.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatService {

    private final RetrievalService retrievalService;
    private final LlmService llmService;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final MessageCitationRepository citationRepository;
    private final ConversationRepository conversationRepository;
    private final TransactionTemplate transactionTemplate;

    public ChatService(RetrievalService retrievalService,
                       LlmService llmService,
                       DocumentRepository documentRepository,
                       MessageRepository messageRepository,
                       MessageCitationRepository citationRepository,
                       ConversationRepository conversationRepository,
                       PlatformTransactionManager transactionManager) {
        this.retrievalService = retrievalService;
        this.llmService = llmService;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.citationRepository = citationRepository;
        this.conversationRepository = conversationRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Value("${verityai.llm.model:openai/gpt-oss-20b}")
    private String model;

    @Value("${verityai.llm.max-tokens:4096}")
    private int maxTokens;

    /**
     * One transaction so the lazy document context and every write commit
     * together. Only a post-write LLM failure is exempt from rollback: the
     * FAILED assistant message is saved before {@link LlmGenerationException} is
     * thrown, so that record must commit. Validation and ownership exceptions
     * keep the default rollback behaviour.
     */
    @Transactional(noRollbackFor = LlmGenerationException.class)
    public MessageResponse ask(Conversation conversation, String question, List<UUID> requestedDocIds) {
        Optional<String> smallTalk = SmallTalk.replyFor(question);
        if (smallTalk.isPresent()) {
            return answerSmallTalk(conversation, question, smallTalk.get(), null);
        }
        List<RetrievalService.RetrievedChunk> evidence = prepare(conversation, question, requestedDocIds);

        LlmService.ChatResponse llmResponse;
        try {
            llmResponse = llmService.generate(chatRequest(question, evidence));
        } catch (Exception e) {
            log.error("LLM generation failed", e);
            throw fail(conversation, e);
        }
        return persist(conversation, evidence, llmResponse);
    }

    /**
     * Streamed variant: each delta goes to {@code onDelta} while the model is
     * answering, and the same message and citations are stored at the end.
     *
     * <p>Deliberately not transactional — the model call can take a minute and
     * holding a database connection for its whole duration would be worse than
     * the small window between the assistant message and its citations. Setup
     * runs before the first delta, so ownership errors still happen before
     * anything streams.</p>
     */
    public MessageResponse streamAsk(Conversation conversation, String question,
                                     List<UUID> requestedDocIds, Consumer<String> onDelta) {
        Optional<String> smallTalk = SmallTalk.replyFor(question);
        if (smallTalk.isPresent()) {
            return answerSmallTalk(conversation, question, smallTalk.get(), onDelta);
        }
        List<RetrievalService.RetrievedChunk> evidence = prepare(conversation, question, requestedDocIds);

        LlmService.ChatResponse llmResponse;
        try {
            llmResponse = llmService.stream(chatRequest(question, evidence), onDelta);
        } catch (Exception e) {
            log.error("Streamed LLM generation failed", e);
            throw fail(conversation, e);
        }
        return persist(conversation, evidence, llmResponse);
    }

    /**
     * Greetings, thanks and "who are you" get a direct reply: no document
     * retrieval, no model call and no citations. Both messages are still stored
     * so the conversation reads naturally when reopened.
     */
    private MessageResponse answerSmallTalk(Conversation conversation, String question,
                                            String reply, Consumer<String> onDelta) {
        if (onDelta != null) {
            onDelta.accept(reply);
        }
        return transactionTemplate.execute(status -> {
            Message userMessage = new Message();
            userMessage.setConversation(conversation);
            userMessage.setRole(Message.MessageRole.USER);
            userMessage.setContent(question);
            userMessage.setStatus(Message.MessageStatus.COMPLETED);
            messageRepository.save(userMessage);

            Message assistantMessage = new Message();
            assistantMessage.setConversation(conversation);
            assistantMessage.setRole(Message.MessageRole.ASSISTANT);
            assistantMessage.setContent(reply);
            assistantMessage.setStatus(Message.MessageStatus.COMPLETED);
            assistantMessage.setModel(SmallTalk.MODEL_NAME);
            assistantMessage.setEvidenceStatus(Message.EvidenceStatus.NONE);
            messageRepository.save(assistantMessage);

            conversationRepository.touch(conversation.getId(), Instant.now());
            return toMessageResponse(assistantMessage, List.of());
        });
    }

    private LlmService.ChatRequest chatRequest(String question, List<RetrievalService.RetrievedChunk> evidence) {
        return new LlmService.ChatRequest(question, evidence, model, maxTokens);
    }

    /**
     * Resolves and checks the document scope, saves the user message and
     * retrieves evidence. Shared by both answer paths so streaming cannot drift
     * from the non-streaming contract.
     */
    private List<RetrievalService.RetrievedChunk> prepare(
            Conversation conversation, String question, List<UUID> requestedDocIds) {
        // A template, not @Transactional, because the streaming path must not
        // hold this transaction (or a pooled connection) for the model call.
        // Inside ask() it simply joins the existing transaction.
        return transactionTemplate.execute(status -> doPrepare(conversation, question, requestedDocIds));
    }

    private List<RetrievalService.RetrievedChunk> doPrepare(
            Conversation conversation, String question, List<UUID> requestedDocIds) {
        UUID userId = conversation.getUser().getId();

        // Explicit ids come from the caller and must all exist; the
        // conversation's own join rows are trusted.
        List<UUID> docIds;
        boolean explicitScope = requestedDocIds != null && !requestedDocIds.isEmpty();
        if (explicitScope) {
            docIds = requestedDocIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        } else {
            docIds = conversation.getDocuments().stream()
                .map(cd -> cd.getDocument().getId())
                .collect(Collectors.toList());
        }

        if (!docIds.isEmpty()) {
            List<Document> docs = documentRepository.findAllById(docIds);
            if (explicitScope && docs.size() != docIds.size()) {
                throw ApiException.notFound("One or more documents were not found");
            }
            for (Document doc : docs) {
                if (!doc.getUser().getId().equals(userId)) {
                    throw ApiException.forbidden("Document access denied");
                }
            }
        }

        Message userMessage = new Message();
        userMessage.setConversation(conversation);
        userMessage.setRole(Message.MessageRole.USER);
        userMessage.setContent(question);
        userMessage.setStatus(Message.MessageStatus.COMPLETED);
        messageRepository.save(userMessage);

        List<RetrievalService.RetrievedChunk> evidence =
            retrievalService.retrieve(userId, question, docIds.isEmpty() ? null : docIds);

        // The prompt and the stored citations read each chunk's document. That
        // association is lazy, so force it here, while the session is open:
        // streamAsk() builds the prompt after this transaction has committed.
        evidence.forEach(chunk -> Hibernate.initialize(chunk.chunk().getDocument()));
        return evidence;
    }

    private MessageResponse persist(Conversation conversation, List<RetrievalService.RetrievedChunk> evidence,
                                    LlmService.ChatResponse llmResponse) {
        Message.EvidenceStatus evidenceStatus;
        if (evidence.isEmpty()) {
            evidenceStatus = Message.EvidenceStatus.NONE;
        } else if (llmResponse.citedIndices().isEmpty()) {
            evidenceStatus = Message.EvidenceStatus.INSUFFICIENT;
        } else {
            evidenceStatus = Message.EvidenceStatus.SUFFICIENT;
        }

        Message assistantMessage = new Message();
        assistantMessage.setConversation(conversation);
        assistantMessage.setRole(Message.MessageRole.ASSISTANT);
        assistantMessage.setContent(llmResponse.content());
        assistantMessage.setStatus(Message.MessageStatus.COMPLETED);
        assistantMessage.setModel(llmResponse.model());
        assistantMessage.setPromptTokens(llmResponse.promptTokens());
        assistantMessage.setCompletionTokens(llmResponse.completionTokens());
        assistantMessage.setLatencyMs(llmResponse.latencyMs());
        assistantMessage.setEvidenceStatus(evidenceStatus);
        messageRepository.save(assistantMessage);

        List<MessageCitation> citations = new ArrayList<>();
        for (int citedIdx : llmResponse.citedIndices()) {
            if (citedIdx >= 0 && citedIdx < evidence.size()) {
                var chunk = evidence.get(citedIdx).chunk();
                MessageCitation citation = new MessageCitation();
                citation.setMessage(assistantMessage);
                citation.setChunk(chunk);
                citation.setDocument(chunk.getDocument());
                citation.setPageNumber(chunk.getPageNumber());
                citation.setExcerpt(evidence.get(citedIdx).getExcerpt());
                citation.setRank((float) evidence.get(citedIdx).rank());
                citation.setCitationIndex(citations.size());
                citations.add(citation);
            }
        }
        citationRepository.saveAll(citations);

        conversationRepository.touch(conversation.getId(), Instant.now());

        return toMessageResponse(assistantMessage, citations);
    }

    /** Records the failure and returns the exception the caller should throw. */
    private LlmGenerationException fail(Conversation conversation, Exception cause) {
        String failureText = LlmErrors.friendlyMessage(cause);
        Message errorMessage = new Message();
        errorMessage.setConversation(conversation);
        errorMessage.setRole(Message.MessageRole.ASSISTANT);
        errorMessage.setContent(failureText);
        errorMessage.setStatus(Message.MessageStatus.FAILED);
        errorMessage.setModel(model);
        messageRepository.save(errorMessage);
        return new LlmGenerationException(failureText);
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

        if (citations != null) {
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
        }

        return resp;
    }

    /**
     * Signals an LLM failure that occurred after the user message and the FAILED
     * assistant message were persisted. This is the only exception type the
     * transaction commits for; every other {@link ApiException} (validation,
     * ownership, conflict) still rolls back.
     */
    static final class LlmGenerationException extends ApiException {
        LlmGenerationException(String message) {
            super(ErrorCode.INTERNAL_ERROR, message, 500);
        }
    }
}
