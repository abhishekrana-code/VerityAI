package com.verityai.controller;

import com.verityai.dto.response.DocumentListResponse;
import com.verityai.dto.response.DocumentResponse;
import com.verityai.dto.response.DocumentUploadResponse;
import com.verityai.entity.Document;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.security.CurrentUser;
import com.verityai.service.document.DocumentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @CurrentUser User user,
            @RequestPart("file") MultipartFile file
    ) {
        Document document = documentService.upload(user.getId(), file);
        DocumentUploadResponse resp = new DocumentUploadResponse();
        resp.setId(document.getId());
        resp.setOriginalFilename(document.getOriginalFilename());
        resp.setStatus(DocumentUploadResponse.DocumentStatus.valueOf(document.getStatus().name()));
        return ResponseEntity.ok(resp);
    }

    @GetMapping
    public ResponseEntity<DocumentListResponse> list(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        Sort.Direction direction = sort.endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        String property = sort.split(",")[0];
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, property));

        Page<Document> documents;
        if (q != null && !q.isBlank()) {
            documents = documentRepository.searchByUserId(user.getId(), q, pageable);
        } else if (status != null && !status.isBlank()) {
            try {
                Document.DocumentStatus docStatus = Document.DocumentStatus.valueOf(status.toUpperCase());
                documents = documentRepository.findByUserIdAndStatusAndDeletedAtIsNull(user.getId(), docStatus, pageable);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Invalid status: " + status);
            }
        } else {
            documents = documentRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId(), pageable);
        }

        DocumentListResponse resp = new DocumentListResponse();
        resp.setContent(documents.map(this::toResponse).getContent());
        resp.setPage(documents.getNumber());
        resp.setSize(documents.getSize());
        resp.setTotalElements(documents.getTotalElements());
        resp.setTotalPages(documents.getTotalPages());
        resp.setFirst(documents.isFirst());
        resp.setLast(documents.isLast());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@CurrentUser User user, @PathVariable UUID id) {
        Document document = documentRepository.findByIdAndUserIdAndDeletedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Document not found"));
        return ResponseEntity.ok(toResponse(document));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentResponse> getStatus(@CurrentUser User user, @PathVariable UUID id) {
        return get(user, id);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<DocumentResponse> retry(@CurrentUser User user, @PathVariable UUID id) {
        Document document = documentService.retry(user.getId(), id);
        return ResponseEntity.ok(toResponse(document));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        documentService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    private DocumentResponse toResponse(Document document) {
        DocumentResponse resp = new DocumentResponse();
        resp.setId(document.getId());
        resp.setOriginalFilename(document.getOriginalFilename());
        resp.setMimeType(document.getMimeType());
        resp.setSizeBytes(document.getSizeBytes());
        resp.setPageCount(document.getPageCount());
        resp.setStatus(DocumentResponse.DocumentStatus.valueOf(document.getStatus().name()));
        resp.setErrorCode(document.getErrorCode());
        resp.setErrorMessage(document.getErrorMessage());
        resp.setCreatedAt(document.getCreatedAt());
        resp.setUpdatedAt(document.getUpdatedAt());
        return resp;
    }
}