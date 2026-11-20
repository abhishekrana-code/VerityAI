package com.verityai.service.knowledge;

import com.verityai.entity.Document;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.repository.KnowledgeEntityRepository;
import com.verityai.repository.KnowledgeRelationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A backfill rebuild rejected by the bounded queue must leave the document's
 * knowledge status pending so the next startup retries it.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphServiceQueueTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentChunkRepository chunkRepository;
    @Mock private DocumentSummaryRepository summaryRepository;
    @Mock private KnowledgeEntityRepository entityRepository;
    @Mock private KnowledgeRelationRepository relationRepository;
    @Mock private TransactionTemplate transactionTemplate;

    @Test
    void backfill_whenQueueIsFull_marksDocumentPendingForBackfill() throws Exception {
        KnowledgeGraphService service = new KnowledgeGraphService(
            documentRepository, chunkRepository, summaryRepository,
            entityRepository, relationRepository, transactionTemplate);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "queueCapacity", 1);
        ReflectionTestUtils.setField(service, "backfillLimit", 10);
        service.initQueue();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();

        Map<UUID, Document> documents = new HashMap<>();
        for (UUID id : new UUID[]{first, second, dropped}) {
            Document doc = new Document();
            doc.setId(id);
            doc.setStatus(Document.DocumentStatus.READY);
            doc.setKnowledgeStatus(Document.KnowledgeStatus.NONE);
            documents.put(id, doc);
        }

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(documentRepository.findDocumentsPendingKnowledge(any(), any()))
            .thenReturn(List.of(first, second, dropped));
        // Hold the single worker inside the first rebuild.
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        });
        when(documentRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.ofNullable(documents.get(id));
        });
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        try {
            service.backfillPendingGraphs();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(documents.get(dropped).getKnowledgeStatus())
                .as("dropped rebuild must stay eligible for the startup backfill")
                .isEqualTo(Document.KnowledgeStatus.PENDING);
            assertThat(documents.get(dropped).getKnowledgeError())
                .as("the user must be told why the graph is still pending")
                .isEqualTo(KnowledgeGraphService.DEFERRED_MESSAGE);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }
}
