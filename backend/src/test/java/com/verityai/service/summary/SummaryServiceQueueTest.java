package com.verityai.service.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Document;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.service.ai.LlmService;
import com.verityai.service.knowledge.KnowledgeGraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A summary job rejected by the bounded queue must leave the document in a
 * state the startup backfill picks up, not disappear silently.
 */
@ExtendWith(MockitoExtension.class)
class SummaryServiceQueueTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentChunkRepository chunkRepository;
    @Mock private DocumentSummaryRepository summaryRepository;
    @Mock private LlmService llmService;

    @Test
    void schedule_whenQueueIsFull_marksDocumentPendingForBackfill() throws Exception {
        SummaryService service = new SummaryService(
            documentRepository, chunkRepository, summaryRepository,
            llmService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "queueCapacity", 1);
        service.initQueue();

        UUID blockingId = UUID.randomUUID();
        UUID queuedId = UUID.randomUUID();
        UUID droppedId = UUID.randomUUID();

        Map<UUID, Document> documents = new HashMap<>();
        for (UUID id : new UUID[]{blockingId, queuedId, droppedId}) {
            Document doc = new Document();
            doc.setId(id);
            doc.setStatus(Document.DocumentStatus.READY);
            doc.setSummaryStatus(Document.SummaryStatus.NONE);
            documents.put(id, doc);
        }

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // The first job blocks inside generate() so the worker and the single
        // queue slot stay occupied while the third job is submitted.
        when(documentRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(blockingId)) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return Optional.ofNullable(documents.get(id));
        });
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        try {
            assertThat(service.schedule(blockingId)).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.schedule(queuedId)).isTrue();
            assertThat(service.schedule(droppedId)).isFalse();

            assertThat(documents.get(droppedId).getSummaryStatus())
                .as("dropped summary must stay eligible for the startup backfill")
                .isEqualTo(Document.SummaryStatus.PENDING);
            assertThat(documents.get(droppedId).getSummaryError())
                .as("the user must be told why the summary is still pending")
                .isEqualTo(SummaryService.DEFERRED_MESSAGE);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }
}
