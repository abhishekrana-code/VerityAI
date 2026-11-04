package com.verityai.service.ai;

import com.verityai.dto.response.MessageResponse;
import com.verityai.entity.Conversation;
import com.verityai.entity.ConversationDocument;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.Message;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ask() orchestration: evidence status derivation,
 * citation mapping, document ownership enforcement and conversation touch.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String QUESTION = "What was the revenue growth?";

    @Mock private RetrievalService retrievalService;
    @Mock private LlmService llmService;
    @Mock private DocumentRepository documentRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageCitationRepository citationRepository;
    @Mock private ConversationRepository conversationRepository;

    private ChatService chatService;
    private User owner;
    private Document document;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
            retrievalService, llmService, documentRepository,
            messageRepository, citationRepository, conversationRepository);

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("owner@example.com");
        owner.setPasswordHash("hash");
        owner.setRole("USER");

        document = new Document();
        document.setId(UUID.randomUUID());
        document.setUser(owner);
        document.setOriginalFilename("report.pdf");
        document.setStoredFilename("report-stored.pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(1024L);
        document.setSha256("a".repeat(64));
        document.setStatus(Document.DocumentStatus.READY);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setUser(owner);
        conversation.setTitle("Test conversation");
    }

    private RetrievalService.RetrievedChunk chunk(String content, int page) {
        DocumentChunk dc = new DocumentChunk();
        dc.setId(UUID.randomUUID());
        dc.setDocument(document);
        dc.setPageNumber(page);
        dc.setChunkIndex(0);
        dc.setContent(content);
        return new RetrievalService.RetrievedChunk(dc, 0.5);
    }

    @Test
    void ask_withCitations_marksEvidenceSufficientAndMapsCitations() {
        RetrievalService.RetrievedChunk evidence = chunk("Revenue grew 14 percent.", 1);
        when(retrievalService.retrieve(owner.getId(), QUESTION, null))
            .thenReturn(List.of(evidence));
        when(llmService.generate(any())).thenReturn(new LlmService.ChatResponse(
            "Revenue grew 14 percent. [S1]", 100, 40, 850, "test-model", List.of(0)));

        MessageResponse resp = chatService.ask(conversation, QUESTION, null);

        assertThat(resp.getEvidenceStatus()).isEqualTo(MessageResponse.EvidenceStatus.SUFFICIENT);
        assertThat(resp.getStatus()).isEqualTo(MessageResponse.MessageStatus.COMPLETED);
        assertThat(resp.getModel()).isEqualTo("test-model");
        assertThat(resp.getCitations()).hasSize(1);

        MessageResponse.CitationResponse citation = resp.getCitations().get(0);
        assertThat(citation.getDocumentName()).isEqualTo("report.pdf");
        assertThat(citation.getPageNumber()).isEqualTo(1);
        assertThat(citation.getCitationIndex()).isZero();
        assertThat(citation.getRank()).isEqualTo(0.5f);
        assertThat(citation.getChunkId()).isEqualTo(evidence.chunk().getId());

        verify(conversationRepository).touch(eq(conversation.getId()), any(Instant.class));

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getRole()).isEqualTo(Message.MessageRole.USER);
        assertThat(saved.getAllValues().get(0).getContent()).isEqualTo(QUESTION);
        assertThat(saved.getAllValues().get(1).getRole()).isEqualTo(Message.MessageRole.ASSISTANT);
        assertThat(saved.getAllValues().get(1).getEvidenceStatus())
            .isEqualTo(Message.EvidenceStatus.SUFFICIENT);
    }

    @Test
    void ask_withoutEvidence_marksEvidenceNone() {
        when(retrievalService.retrieve(owner.getId(), QUESTION, null)).thenReturn(List.of());
        when(llmService.generate(any())).thenReturn(new LlmService.ChatResponse(
            "Insufficient evidence to answer.", 30, 10, 200, "test-model", List.of()));

        MessageResponse resp = chatService.ask(conversation, QUESTION, null);

        assertThat(resp.getEvidenceStatus()).isEqualTo(MessageResponse.EvidenceStatus.NONE);
        assertThat(resp.getCitations()).isEmpty();
        verify(citationRepository).saveAll(any());
        verify(conversationRepository).touch(eq(conversation.getId()), any(Instant.class));
    }

    @Test
    void ask_evidenceButNoCitations_marksEvidenceInsufficient() {
        when(retrievalService.retrieve(owner.getId(), QUESTION, null))
            .thenReturn(List.of(chunk("Some ambiguous text.", 2)));
        when(llmService.generate(any())).thenReturn(new LlmService.ChatResponse(
            "The evidence is ambiguous.", 50, 20, 300, "test-model", List.of()));

        MessageResponse resp = chatService.ask(conversation, QUESTION, null);

        assertThat(resp.getEvidenceStatus()).isEqualTo(MessageResponse.EvidenceStatus.INSUFFICIENT);
        assertThat(resp.getCitations()).isEmpty();
    }

    @Test
    void ask_withForeignDocument_throwsForbiddenBeforeSavingAnything() {
        User other = new User();
        other.setId(UUID.randomUUID());
        other.setEmail("other@example.com");
        other.setPasswordHash("hash");
        Document foreign = new Document();
        foreign.setId(UUID.randomUUID());
        foreign.setUser(other);
        foreign.setOriginalFilename("secret.pdf");
        foreign.setStoredFilename("secret-stored.pdf");
        foreign.setSizeBytes(10L);
        foreign.setSha256("b".repeat(64));
        when(documentRepository.findAllById(List.of(foreign.getId()))).thenReturn(List.of(foreign));

        List<UUID> requested = List.of(foreign.getId());
        assertThatThrownBy(() -> chatService.ask(conversation, QUESTION, requested))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(403));

        verify(messageRepository, never()).save(any());
        verify(llmService, never()).generate(any());
        verify(conversationRepository, never()).touch(any(), any());
    }

    @Test
    void ask_withConversationDocuments_scopesRetrievalToThoseDocuments() {
        ConversationDocument cd = new ConversationDocument();
        cd.setConversationId(conversation.getId());
        cd.setDocumentId(document.getId());
        cd.setDocument(document);
        conversation.getDocuments().add(cd);

        when(documentRepository.findAllById(List.of(document.getId())))
            .thenReturn(List.of(document));
        when(retrievalService.retrieve(owner.getId(), QUESTION, List.of(document.getId())))
            .thenReturn(List.of());
        when(llmService.generate(any())).thenReturn(new LlmService.ChatResponse(
            "Insufficient evidence to answer.", 10, 5, 100, "test-model", List.of()));

        MessageResponse resp = chatService.ask(conversation, QUESTION, null);

        assertThat(resp.getEvidenceStatus()).isEqualTo(MessageResponse.EvidenceStatus.NONE);
        verify(retrievalService).retrieve(owner.getId(), QUESTION, List.of(document.getId()));
    }

    @Test
    void ask_whenLlmFails_savesFailedAssistantMessageAndThrows() {
        when(retrievalService.retrieve(owner.getId(), QUESTION, null)).thenReturn(List.of());
        when(llmService.generate(any())).thenThrow(new RuntimeException("provider down"));

        assertThatThrownBy(() -> chatService.ask(conversation, QUESTION, null))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(500));

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        Message failed = saved.getAllValues().get(1);
        assertThat(failed.getRole()).isEqualTo(Message.MessageRole.ASSISTANT);
        assertThat(failed.getStatus()).isEqualTo(Message.MessageStatus.FAILED);
        assertThat(failed.getContent()).startsWith("Error generating answer");
        verify(conversationRepository, never()).touch(any(), any());
    }
}
