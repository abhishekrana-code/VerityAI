package com.verityai.service.document;

import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

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

    @Transactional
    public Document upload(UUID userId, MultipartFile file) {
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
        document.setMimeType(file.getContentType());
        document.setSizeBytes(file.getSize());
        document.setSha256(sha256);
        document.setStatus(Document.DocumentStatus.UPLOADED);

        document = documentRepository.save(document);

        // Process asynchronously
        Document savedDocument = document;
        CompletableFuture.runAsync(() -> processDocument(savedDocument.getId(), filePath));

        return document;
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.validation("File is empty");
        }
        if (file.getSize() > maxFileSizeMb * 1024L * 1024L) {
            throw ApiException.internal("File too large: max " + maxFileSizeMb + "MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw ApiException.internal("Only PDF files are supported");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw ApiException.internal("File must have .pdf extension");
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
        if (document == null) return;

        document.setStatus(Document.DocumentStatus.PROCESSING);
        documentRepository.save(document);

        try (PDDocument pdf = Loader.loadPDF(filePath.toFile())) {
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
                throw new IllegalStateException("No extractable text found in PDF");
            }

            chunkRepository.saveAll(chunks);

            document.setPageCount(pageCount);
            document.setStatus(Document.DocumentStatus.READY);
            documentRepository.save(document);

            log.info("Processed document {}: {} pages, {} chunks", documentId, pageCount, chunks.size());

        } catch (Exception e) {
            log.error("Failed to process document {}", documentId, e);
            document.setStatus(Document.DocumentStatus.FAILED);
            document.setErrorCode("PROCESSING_FAILED");
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);
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

            start = end - chunkOverlapChars;
            if (start < 0) start = 0;
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

        if (document.getStatus() != Document.DocumentStatus.FAILED) {
            throw ApiException.validation("Only failed documents can be retried");
        }

        chunkRepository.deleteByDocumentId(documentId);

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
            throw ApiException.internal("Original file missing");
        }

        CompletableFuture.runAsync(() -> processDocument(documentId, filePath));

        return document;
    }

    @Transactional
    public void delete(UUID userId, UUID documentId) {
        Document document = documentRepository.findByIdAndUserIdAndDeletedAtIsNull(documentId, userId)
            .orElseThrow(() -> ApiException.notFound("Document not found"));

        document.setDeletedAt(Instant.now());
        documentRepository.save(document);

        try {
            Path filePath = uploadPath.resolve(document.getStoredFilename());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file for document {}", documentId, e);
        }
    }
}