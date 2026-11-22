package com.verityai.service.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.dto.response.ClaimVerificationResponse;
import com.verityai.dto.response.ClaimVerificationResponse.Mode;
import com.verityai.dto.response.ClaimVerificationResponse.Status;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.Message;
import com.verityai.entity.MessageCitation;
import com.verityai.entity.MessageClaimVerification;
import com.verityai.entity.MessageClaimVerification.SupportClass;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageClaimVerificationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.service.ai.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The quick layer must be free and honest: it records citation coverage without
 * calling a model and never classifies a cited claim as supported. The deep
 * layer still needs the model; when it fails nothing is stored.
 */
class ClaimVerificationQuickModeTest {

    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final MessageCitationRepository citationRepository = mock(MessageCitationRepository.class);
    private final MessageClaimVerificationRepository claimRepository =
        mock(MessageClaimVerificationRepository.class);
    private final LlmService llmService = mock(LlmService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private ClaimVerificationService service;
    private UUID conversationId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        service = new ClaimVerificationService(
            messageRepository, citationRepository, claimRepository, llmService,
            new ObjectMapper(), transactionTemplate);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "modelOverride", "");
        ReflectionTestUtils.setField(service, "maxClaims", 25);
        ReflectionTestUtils.setField(service, "maxTokens", 2500);

        // The mocked transaction template must run the callback so saveAll is reached.
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work =
                invocation.getArgument(0);
            work.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);

        Message message = new Message();
        message.setId(messageId);
        message.setConversation(conversation);
        message.setRole(Message.MessageRole.ASSISTANT);
        message.setStatus(Message.MessageStatus.COMPLETED);
        message.setContent("Revenue grew 14 percent in 2024. [S1]\nCosts fell 3 percent. [S2]");

        when(messageRepository.findByIdWithConversation(messageId)).thenReturn(Optional.of(message));
        when(citationRepository.findByMessageIdInWithDocument(List.of(messageId)))
            .thenReturn(List.of(citation(0), citation(1)));
        when(claimRepository.saveAll(anyList())).thenReturn(List.of());
    }

    private static MessageCitation citation(int index) {
        // The deep prompt lists each citation's file name, so a citation without
        // its document would fail while building the prompt.
        Document document = new Document();
        document.setOriginalFilename("report-" + index + ".pdf");

        MessageCitation citation = new MessageCitation();
        citation.setDocument(document);
        citation.setCitationIndex(index);
        citation.setPageNumber(1);
        citation.setExcerpt("Excerpt " + index);
        citation.setRank(1.0f);
        return citation;
    }

    @Test
    void quickCheckRecordsCoverageWithoutCallingTheModel() {
        ClaimVerificationResponse response =
            service.verify(conversationId, messageId, Mode.QUICK);

        assertThat(response.getMode()).isEqualTo(Mode.QUICK);
        assertThat(response.getStatus()).isEqualTo(Status.PARTIAL);
        assertThat(response.getModel()).isNull();
        assertThat(response.getCoverage()).isEqualTo(1.0);
        assertThat(response.getClaims()).hasSize(2);
        assertThat(response.getClaims())
            .as("citing a source is not the same as being supported by it")
            .allSatisfy(claim -> assertThat(claim.getSupportClass()).isNull());

        verifyNoInteractions(llmService);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageClaimVerification>> rows = ArgumentCaptor.forClass(List.class);
        verify(claimRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.getSupportClass()).isNull();
            assertThat(row.getModel()).isNull();
            assertThat(row.getVerifiedAt()).isNotNull();
        });
    }

    @Test
    void quickCheckKeepsAnAlreadyStoredDeepResult() {
        MessageClaimVerification deepRow = new MessageClaimVerification();
        deepRow.setClaimIndex(0);
        deepRow.setClaimText("Revenue grew 14 percent in 2024. [S1]");
        deepRow.setSupportClass(SupportClass.SUPPORTED);
        deepRow.setCitationIndexes(List.of(0));
        deepRow.setModel("test-model");
        deepRow.setVerifiedAt(Instant.now());
        when(claimRepository.findByMessageIdOrderByClaimIndexAsc(messageId)).thenReturn(List.of(deepRow));

        ClaimVerificationResponse response = service.verify(conversationId, messageId, Mode.QUICK);

        assertThat(response.getMode()).isEqualTo(Mode.DEEP);
        assertThat(response.getClaims()).singleElement()
            .satisfies(claim -> assertThat(claim.getSupportClass()).isEqualTo("SUPPORTED"));
        verifyNoInteractions(llmService);
        verify(claimRepository, never()).saveAll(anyList());
        verify(claimRepository, never()).deleteByMessageId(messageId);
    }

    @Test
    void deepCheckStillCallsTheModelAndStoresNothingWhenItFails() {
        when(llmService.complete(any())).thenThrow(new RuntimeException("no API key configured"));

        ClaimVerificationResponse response =
            service.verify(conversationId, messageId, Mode.DEEP);

        assertThat(response.getMode()).isEqualTo(Mode.DEEP);
        assertThat(response.getStatus()).isEqualTo(Status.FAILED);
        assertThat(response.getError()).contains("Model call failed");
        verify(llmService).complete(any());
        verify(claimRepository, never()).saveAll(anyList());
    }
}
