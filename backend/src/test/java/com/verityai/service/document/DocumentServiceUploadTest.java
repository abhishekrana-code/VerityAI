package com.verityai.service.document;

import com.verityai.config.ProcessingConfig;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.service.contradiction.ContradictionService;
import com.verityai.service.embedding.EmbeddingIndexService;
import com.verityai.service.knowledge.KnowledgeGraphService;
import com.verityai.service.summary.SummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The upload writes the PDF to disk before the database row exists, so a failed
 * persist must clean the file up instead of orphaning it.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceUploadTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentChunkRepository chunkRepository;
    @Mock private ProcessingEventService processingEventService;
    @Mock private SummaryService summaryService;
    @Mock private EmbeddingIndexService embeddingIndexService;
    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private ContradictionService contradictionService;
    @Mock private ProcessingConfig.ProcessingExecutor processingExecutor;

    @TempDir
    Path uploadDir;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
            documentRepository, chunkRepository, processingEventService, summaryService,
            embeddingIndexService, knowledgeGraphService, contradictionService, processingExecutor);

        ReflectionTestUtils.setField(documentService, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(documentService, "maxFileSizeMb", 25);
        ReflectionTestUtils.setField(documentService, "maxPages", 200);
        ReflectionTestUtils.setField(documentService, "chunkSizeChars", 1000);
        ReflectionTestUtils.setField(documentService, "chunkOverlapChars", 150);
        documentService.init();
    }

    @Test
    void upload_whenPersistingFails_deletesTheStoredFile() throws IOException {
        UUID userId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf",
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes());

        when(processingExecutor.hasCapacity()).thenReturn(true);
        when(documentRepository.findByUserIdAndSha256AndDeletedAtIsNull(eq(userId), anyString()))
            .thenReturn(Optional.empty());
        when(documentRepository.save(any()))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> documentService.upload(userId, file))
            .isInstanceOf(DataIntegrityViolationException.class);

        try (Stream<Path> remaining = Files.list(uploadDir)) {
            assertThat(remaining.toList()).as("stored PDF after a failed save").isEmpty();
        }
    }
}
