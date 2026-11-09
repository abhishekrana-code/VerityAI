package com.verityai.service.document;

import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.DocumentProcessingEvent;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.service.summary.SummaryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ProcessingEventService processingEventService;
    private final com.verityai.service.summary.SummaryService summaryService;
    private final com.verityai.service.embedding.EmbeddingIndexService embeddingIndexService;
    private final com.verityai.service.knowledge.KnowledgeGraphService knowledgeGraphService;
    private final com.verityai.service.contradiction.ContradictionService contradictionService;
    private final com.verityai.config.ProcessingConfig.ProcessingExecutor processingExecutor;

    @Value("${verityai.upload.dir:/var/lib/verityai/uploads}")
    private String uploadDir;

    @Value("${verityai.upload.max-file-size-mb:25}")
    private int maxFileSizeMb;

    @Value("${verityai.upload.max-pages:200}")
    private int maxPages;

    @Value("${verityai.document.chunk-size-chars:1000}")
    private int chunkSizeChars;

    @Value("${verityai.document.chunk-overlap-chars:150}")
    private int chunkOverlapChars;

    private Path uploadPath;

    @PostConstruct
    public void init() {
        this.uploadPath = Paths.get(uploadDir).toAbsolutePath();
        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            log.error("Failed to create upload directory: {}", uploadPath, e);
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverStuckDocuments() {
        try {
            List<Document> stuckDocs = documentRepository.findByStatusInAndDeletedAtIsNull(
                List.of(Document.DocumentStatus.PROCESSING, Document.DocumentStatus.UPLOADED)
            );
            if (!stuckDocs.isEmpty()) {
                log.info("Found {} interrupted/stuck documents on startup, recovering...", stuckDocs.size());
                for (Document doc : stuckDocs) {
                    Path filePath = uploadPath.resolve(doc.getStoredFilename());
                    if (Files.exists(filePath)) {
                        log.info("Recovering document {} ({}), queuing for reprocessing...", doc.getId(), doc.getOriginalFilename());
                        try {
                            processingExecutor.submit(() -> processDocument(doc.getId(), filePath));
                        } catch (org.springframework.core.task.TaskRejectedException e) {
                            log.warn("Processing queue full during recovery; document {} will retry on next start", doc.getId());
                        }
                    } else {
                        doc.setStatus(Document.DocumentStatus.FAILED);
                        doc.setErrorCode("INTERRUPTED");
                        doc.setErrorMessage("Processing was interrupted when the server restarted and the file is not on disk");
                        documentRepository.save(doc);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to recover stuck documents on startup", e);
        }
    }

    @Transactional
    public Document upload(UUID userId, MultipartFile file) {
        // Reject early when the bounded processing pool cannot accept work.
        // Uploads must never fall back to doing PDF work on the request thread.
        if (!processingExecutor.hasCapacity()) {
            throw ApiException.queueFull(
                "Document processing queue is full, please retry shortly", 30);
        }
        validateFile(file);

        String sha256;
        try {
            sha256 = computeSha256(file.getInputStream());
        } catch (IOException e) {
            throw ApiException.internal("Failed to compute file hash");
        }

        var existing = documentRepository.findBySha256AndDeletedAtIsNull(sha256);
        if (existing.isPresent()) {
            throw ApiException.conflict("Document already exists");
        }

        String storedFilename = UUID.randomUUID() + ".pdf";
        Path filePath = uploadPath.resolve(storedFilename);

        try {
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            throw ApiException.internal("Failed to store file");
        }

        Document document = new Document();
        document.setUser(new User());
        document.getUser().setId(userId);
        document.setOriginalFilename(file.getOriginalFilename());
        document.setStoredFilename(storedFilename);
        // We verified the %PDF- signature, so store the detected type rather
        // than trusting the browser-supplied Content-Type.
        document.setMimeType("application/pdf");
        document.setSizeBytes(file.getSize());
        document.setSha256(sha256);
        document.setStatus(Document.DocumentStatus.UPLOADED);

        document = documentRepository.save(document);
        processingEventService.recordUploaded(document.getId());

        // Process asynchronously after the transaction commits
        Document savedDocument = document;
        runAfterCommit(savedDocument.getId(), () -> processDocument(savedDocument.getId(), filePath));

        return document;
    }

    private void runAfterCommit(UUID documentId, Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitOrMarkRejected(documentId, task);
                }
            });
        } else {
            submitOrMarkRejected(documentId, task);
        }
    }

    /**
     * The capacity check in {@link #upload} is an early-out, not a reservation.
     * If the pool fills between check and commit, record a retryable failure
     * instead of throwing into the transaction callback (which would leave the
     * document stuck in UPLOADED until the next restart).
     */
    private void submitOrMarkRejected(UUID documentId, Runnable task) {
        try {
            processingExecutor.submit(task);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.warn("Processing queue rejected document {}; marking it FAILED for retry", documentId);
            try {
                documentRepository.findById(documentId).ifPresent(doc -> {
                    if (doc.getDeletedAt() == null) {
                        doc.setStatus(Document.DocumentStatus.FAILED);
                        doc.setErrorCode("QUEUE_FULL");
                        doc.setErrorMessage("Processing queue was full. Please delete this upload and try again.");
                        documentRepository.save(doc);
                    }
                });
            } catch (Exception saveEx) {
                log.error("Failed to mark rejected document {} as FAILED", documentId, saveEx);
            }
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.validation("File is empty");
        }
        if (file.getSize() > maxFileSizeMb * 1024L * 1024L) {
            throw ApiException.validation("File too large: max " + maxFileSizeMb + "MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw ApiException.validation("Only PDF files are supported");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw ApiException.validation("File must have .pdf extension");
        }
        // Content-Type and extension are attacker-controlled. The PDF spec
        // allows leading bytes, so require the %PDF- signature within the
        // first 1024 bytes before storing anything.
        try (java.io.InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(1024);
            String head = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!head.contains("%PDF-")) {
                throw ApiException.validation("File is not a valid PDF (missing %PDF- header)");
            }
        } catch (IOException e) {
            throw ApiException.validation("Could not read uploaded file");
        }
    }

    private String computeSha256(java.io.InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void processDocument(UUID documentId, Path filePath) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("Document {} not found for processing", documentId);
            return;
        }

        // Conditional status update: if the document was deleted after it was
        // queued, this affects 0 rows and we stop instead of resurrecting it
        // through a stale entity merge (soft delete keeps the row).
        if (documentRepository.updateStatusIfNotDeleted(documentId, Document.DocumentStatus.PROCESSING) == 0) {
            log.info("Document {} was deleted before processing started; skipping", documentId);
            return;
        }
        int attempt = processingEventService.beginAttempt(documentId);
        log.info("Starting processing for document {} at {} (attempt {})", documentId, filePath, attempt);

        try (PDDocument pdf = Loader.loadPDF(filePath.toFile())) {
            processingEventService.record(documentId, attempt, DocumentProcessingEvent.Stage.EXTRACTING);
            if (pdf.isEncrypted()) {
                throw new IllegalStateException("PDF is password-protected or encrypted");
            }
            int pageCount = pdf.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new IllegalStateException("Document exceeds max pages: " + maxPages);
            }
            if (pageCount == 0) {
                throw new IllegalStateException("PDF has no pages");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            List<DocumentChunk> chunks = new ArrayList<>();
            int chunkIndex = 0;

            for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String pageText = stripper.getText(pdf);

                if (pageText == null || pageText.trim().isEmpty()) {
                    continue;
                }

                List<String> pageChunks = chunkText(pageText);
                for (String chunkText : pageChunks) {
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setDocument(document);
                    chunk.setPageNumber(pageNum);
                    chunk.setChunkIndex(chunkIndex++);
                    chunk.setContent(chunkText);
                    chunk.setTokenCount(estimateTokens(chunkText));
                    chunks.add(chunk);
                }
            }

            if (chunks.isEmpty()) {
                throw new IllegalStateException("SCANNED_OR_EMPTY_PDF");
            }

            processingEventService.record(documentId, attempt, DocumentProcessingEvent.Stage.CHUNKING);
            processingEventService.record(documentId, attempt, DocumentProcessingEvent.Stage.INDEXING);
            chunkRepository.saveAll(chunks);

            // Phase 2: semantic indexing during INDEXING (best-effort — failures
            // leave embeddings NULL and are retried by the backfill; retrieval
            // degrades gracefully to keyword search meanwhile).
            embeddingIndexService.embedDocument(documentId);

            // Targeted update (status + page count only) so a concurrent soft
            // delete cannot be undone by this stale entity.
            documentRepository.updateProcessingResult(
                documentId, Document.DocumentStatus.READY, pageCount);

            // Phase 3: deterministic knowledge-graph extraction. Runs after the
            // document is READY (the builder only reads READY documents) and is
            // best-effort: a graph failure is recorded on the document and never
            // blocks indexing or retrieval.
            knowledgeGraphService.rebuild(documentId);

            processingEventService.record(documentId, attempt, DocumentProcessingEvent.Stage.COMPLETED);

            log.info("Processed document {}: {} pages, {} chunks", documentId, pageCount, chunks.size());

            // AI summaries are generated asynchronously after indexing succeeds
            summaryService.schedule(documentId);

        } catch (Throwable e) {
            log.error("Failed to process document {}", documentId, e);
            try {
                Document fresh = documentRepository.findById(documentId).orElse(null);
                if (fresh != null) {
                    fresh.setStatus(Document.DocumentStatus.FAILED);

                    String code = "PROCESSING_FAILED";
                    String message = e.getMessage() != null ? e.getMessage() : "Unknown error during document processing";

                    if ("SCANNED_OR_EMPTY_PDF".equals(message) || message.contains("No extractable text")) {
                        code = "SCANNED_PDF_NO_TEXT";
                        message = "No extractable digital text found. This document appears to be a scanned image or photo without selectable text. VerityAI requires digital text to ground answers with citations (OCR for scanned files is planned for a future update).";
                    } else if (message.toLowerCase().contains("encrypted") || message.toLowerCase().contains("password")) {
                        code = "ENCRYPTED_PDF";
                        message = "This PDF is encrypted or password-protected. Please upload an unlocked PDF.";
                    } else if (message.contains("exceeds max pages")) {
                        code = "PAGE_LIMIT_EXCEEDED";
                    }

                    fresh.setErrorCode(code);
                    fresh.setErrorMessage(message);
                    documentRepository.save(fresh);
                    processingEventService.recordFailure(documentId, attempt, code, message);
                }
            } catch (Exception saveEx) {
                log.error("Failed to mark document {} as FAILED", documentId, saveEx);
            }
        }
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + chunkSizeChars, length);

            // Try to break at sentence boundary
            if (end < length) {
                int lastPeriod = text.lastIndexOf(". ", end);
                int lastNewline = text.lastIndexOf("\n", end);
                int breakPoint = Math.max(lastPeriod, lastNewline);
                if (breakPoint > start + chunkSizeChars / 2) {
                    end = breakPoint + 1;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= length) {
                break;
            }

            start = Math.max(end - chunkOverlapChars, start + 1);
        }

        return chunks;
    }

    private int estimateTokens(String text) {
        // Rough estimate: ~4 chars per token for English
        return Math.max(1, text.length() / 4);
    }

    @Transactional
    public Document retry(UUID userId, UUID documentId) {
        Document document = documentRepository.findByIdAndUserIdAndDeletedAtIsNull(documentId, userId)
            .orElseThrow(() -> ApiException.notFound("Document not found"));

        if (!processingExecutor.hasCapacity()) {
            throw ApiException.queueFull(
                "Document processing queue is full, please retry shortly", 30);
        }

        if (document.getStatus() == Document.DocumentStatus.READY) {
            throw ApiException.validation("Document is already processed");
        }

        chunkRepository.deleteByDocumentId(documentId);
        // Reprocessing invalidates any summaries and graph derived from the old chunks
        summaryService.regenerateReset(documentId);
        knowledgeGraphService.deleteForDocument(documentId);
        contradictionService.deleteForDocument(documentId);

        document.setStatus(Document.DocumentStatus.UPLOADED);
        document.setErrorCode(null);
        document.setErrorMessage(null);
        documentRepository.save(document);

        Path filePath = uploadPath.resolve(document.getStoredFilename());
        if (!Files.exists(filePath)) {
            document.setStatus(Document.DocumentStatus.FAILED);
            document.setErrorCode("FILE_MISSING");
            document.setErrorMessage("Original file not found on disk");
            documentRepository.save(document);
            processingEventService.recordFailure(documentId,
                processingEventService.nextAttempt(documentId), "FILE_MISSING",
                "Original file not found on disk");
            throw ApiException.conflict(
                "Original PDF file is missing on the server - delete this document and re-upload it");
        }

        runAfterCommit(documentId, () -> processDocument(documentId, filePath));

        return document;
    }

    /** Absolute path of a document's stored PDF (used by the preview endpoint). */
    public Path resolvePath(Document document) {
        return uploadPath.resolve(document.getStoredFilename());
    }

    @Transactional
    public void delete(UUID userId, UUID documentId) {
        Document document = documentRepository.findByIdAndUserIdAndDeletedAtIsNull(documentId, userId)
            .orElseThrow(() -> ApiException.notFound("Document not found"));

        document.setDeletedAt(Instant.now());
        documentRepository.save(document);

        // Graph rows are derived content; remove them with the document.
        knowledgeGraphService.deleteForDocument(documentId);
        contradictionService.deleteForDocument(documentId);

        try {
            Path filePath = uploadPath.resolve(document.getStoredFilename());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file for document {}", documentId, e);
        }
    }
}