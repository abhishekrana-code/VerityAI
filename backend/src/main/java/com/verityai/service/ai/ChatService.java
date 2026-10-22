package com.verityai.service.ai;

import com.verityai.dto.response.MessageResponse;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.service.retrieval.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final RetrievalService retrievalService;
    private final LlmService llmService;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final MessageCitationRepository citationRepository;

    @Transactional
    public MessageResponse ask(Conversation conversation, String question, List<UUID> requestedDocIds) {
        UUID userId = conversation.getUser().getId();

        // Determine document scope
        List<UUID> docIds;
        if (requestedDocIds != null && !requestedDocIds.isEmpty()) {
            docIds = requestedDocIds;
        } else {
            docIds = conversation.getDocuments().stream()
                .map(cd -> cd.getDocument().getId())
                .collect(Collectors.toList());
        }

        // Verify ownership
        if (!docIds.isEmpty()) {
            List<Document> docs = documentRepository.findAllById(docIds);
            for (Document doc : docs) {
                if (!doc.getUser().getId().equals(userId)) {
                    throw ApiException.forbidden("Document access denied");
                }
            }
        }

        // Save user message
        Message userMessage = new Message();
        userMessage.setConversation(conversation);
        userMessage.setRole(Message.MessageRole.USER);
        userMessage.setContent(question);
        userMessage.setStatus(Message.MessageStatus.COMPLETED);
        messageRepository.save(userMessage);

        // Retrieve evidence
        List<RetrievalService.RetrievedChunk> evidence = retrievalService.retrieve(userId, question);

        // Generate answer
        LlmService.ChatRequest chatRequest = new LlmService.ChatRequest(
            question,
            evidence,
            "openai/gpt-oss-20b",
            4096
        );

        LlmService.ChatResponse llmResponse;
        try {
            llmResponse = llmService.generate(chatRequest);
        } catch (Exception e) {
            log.error("LLM generation failed", e);
            Message errorMessage = new Message();
            errorMessage.setConversation(conversation);
            errorMessage.setRole(Message.MessageRole.ASSISTANT);
            errorMessage.setContent("Error generating answer: " + e.getMessage());
            errorMessage.setStatus(Message.MessageStatus.FAILED);
            errorMessage.setModel("openai/gpt-oss-20b");
            messageRepository.save(errorMessage);
            throw ApiException.internal("Failed to generate answer");
        }

        // Determine evidence status
        Message.EvidenceStatus evidenceStatus;
        if (evidence.isEmpty()) {
            evidenceStatus = Message.EvidenceStatus.NONE;
        } else if (llmResponse.citedIndices().isEmpty()) {
            evidenceStatus = Message.EvidenceStatus.INSUFFICIENT;
        } else {
            evidenceStatus = Message.EvidenceStatus.SUFFICIENT;
        }

        // Save assistant message
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

        // Save citations
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

        // Update conversation timestamp
        conversation.setUpdatedAt(Instant.now());

        return toMessageResponse(assistantMessage, citations);
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
}