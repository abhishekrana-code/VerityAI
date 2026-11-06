package com.verityai.controller;

import com.verityai.dto.request.BulkDeleteRequest;
import com.verityai.dto.response.BulkDeleteResponse;
import com.verityai.dto.response.DocumentListResponse;
import com.verityai.dto.response.DocumentResponse;
import com.verityai.dto.response.DocumentSummariesResponse;
import com.verityai.dto.response.DocumentUploadResponse;
import com.verityai.dto.response.ProcessingHistoryResponse;
import com.verityai.dto.response.TagResponse;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentProcessingEvent;
import com.verityai.entity.DocumentSummary;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentProcessingEventRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.security.CurrentUser;
import com.verityai.security.RateLimitService;
import com.verityai.service.document.CollectionService;
import com.verityai.service.document.DocumentService;
import com.verityai.service.document.ProcessingEventService;
import com.verityai.service.document.TagService;
import com.verityai.service.summary.SummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentSummaryRepository summaryRepository;
    private final DocumentProcessingEventRepository processingEventRepository;
    private final ProcessingEventService processingEventService;
    private final SummaryService summaryService;
    private final TagService tagService;
    private final CollectionService collectionService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    /**
     * Sort whitelist: UI-facing names (and raw entity property aliases for
     * backward compatibility with existing clients) mapped to entity
     * properties. Anything else falls back to createdAt so a bad query param
     * can never reach the JPA metamodel.
     */
    private static final Map<String, String> SORT_FIELDS = Map.ofEntries(
        Map.entry("name", "originalFilename"),
        Map.entry("originalFilename", "originalFilename"),
        Map.entry("date", "createdAt"),
        Map.entry("created", "createdAt"),
        Map.entry("createdAt", "createdAt"),
        Map.entry("updated", "updatedAt"),
        Map.entry("updatedAt", "updatedAt"),
        Map.entry("size", "sizeBytes"),
        Map.entry("sizeBytes", "sizeBytes"),
        Map.entry("status", "status"),
        Map.entry("pages", "pageCount"),
        Map.entry("pageCount", "pageCount")
    );

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @CurrentUser User user,
            @RequestPart("file") MultipartFile file
    ) {
        rateLimitService.checkUpload(user.getId());
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
            @RequestParam(required = false) UUID tag,
            @RequestParam(required = false) UUID collection,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        Page<Document> documents = documentRepository.findAll(buildSpec(user.getId(), q, status, tag, collection), pageable);

        DocumentListResponse resp = new DocumentListResponse();
        resp.setContent(enrich(documents.getContent(), user.getId()));
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
        Document document = requireOwned(user, id);
        return ResponseEntity.ok(enrich(List.of(document), user.getId()).get(0));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentResponse> getStatus(@CurrentUser User user, @PathVariable UUID id) {
        return get(user, id);
    }

    // ------------------------------------------------------------------
    // Processing history (persistent, survives restarts)
    // ------------------------------------------------------------------

    @GetMapping("/{id}/processing")
    public ResponseEntity<ProcessingHistoryResponse> processing(@CurrentUser User user, @PathVariable UUID id) {
        requireOwned(user, id);
        List<DocumentProcessingEvent> events = processingEventService.history(id);

        ProcessingHistoryResponse resp = new ProcessingHistoryResponse();
        resp.setDocumentId(id);
        resp.setEvents(events.stream().map(e -> {
            ProcessingHistoryResponse.Event ev = new ProcessingHistoryResponse.Event();
            ev.setId(e.getId());
            ev.setAttempt(e.getAttempt());
            ev.setStage(e.getStage().name());
            ev.setErrorCode(e.getErrorCode());
            ev.setErrorMessage(e.getErrorMessage());
            ev.setCreatedAt(e.getCreatedAt());
            return ev;
        }).collect(Collectors.toList()));

        if (!events.isEmpty()) {
            // history is newest-first, so the first row is the current state
            resp.setCurrentStage(events.get(0).getStage().name());
            resp.setCurrentAttempt(events.stream().mapToInt(DocumentProcessingEvent::getAttempt).max().orElse(1));
        } else {
            resp.setCurrentStage("UPLOADED");
            resp.setCurrentAttempt(1);
        }
        return ResponseEntity.ok(resp);
    }

    // ------------------------------------------------------------------
    // AI summaries
    // ------------------------------------------------------------------

    @GetMapping("/{id}/summaries")
    public ResponseEntity<DocumentSummariesResponse> summaries(@CurrentUser User user, @PathVariable UUID id) {
        Document document = requireOwned(user, id);

        DocumentSummariesResponse resp = new DocumentSummariesResponse();
        resp.setDocumentId(id);
        resp.setSummaryStatus(document.getSummaryStatus() == null
            ? "NONE" : document.getSummaryStatus().name());
        resp.setSummaryError(document.getSummaryError());
        resp.setSummaries(summaryRepository.findByDocumentIdOrderByKind(id).stream().map(s -> {
            DocumentSummariesResponse.Section sec = new DocumentSummariesResponse.Section();
            sec.setId(s.getId());
            sec.setKind(s.getKind().name());
            sec.setSource(s.getSource().name());
            sec.setContent(s.getContent() != null ? s.getContent() : objectMapper.createObjectNode());
            sec.setModel(s.getModel());
            sec.setGeneratedAt(s.getGeneratedAt());
            return sec;
        }).collect(Collectors.toList()));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{id}/summaries/regenerate")
    public ResponseEntity<DocumentSummariesResponse> regenerateSummaries(@CurrentUser User user, @PathVariable UUID id) {
        Document document = requireOwned(user, id);
        if (document.getStatus() != Document.DocumentStatus.READY) {
            throw ApiException.validation("Summaries are only available for processed documents");
        }
        // Regeneration is an AI request: same per-user budget as chat
        rateLimitService.checkAiRequest(user.getId());
        summaryService.regenerate(id);
        return ResponseEntity.ok(summaries(user, id).getBody());
    }

    // ------------------------------------------------------------------
    // PDF preview
    // ------------------------------------------------------------------

    @GetMapping("/{id}/file")
    public ResponseEntity<org.springframework.core.io.Resource> file(@CurrentUser User user, @PathVariable UUID id) {
        Document document = requireOwned(user, id);
        Path path = documentService.resolvePath(document);
        if (!Files.exists(path)) {
            throw ApiException.notFound("File no longer exists on disk");
        }
        try {
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(Files.size(path))
                .header("Content-Disposition",
                    "inline; filename=\"" + document.getOriginalFilename().replace("\"", "") + "\"")
                .body(new org.springframework.core.io.FileSystemResource(path));
        } catch (java.io.IOException e) {
            throw ApiException.internal("Failed to read file");
        }
    }

    // ------------------------------------------------------------------
    // Tag attachment (tags themselves live under /api/v1/tags)
    // ------------------------------------------------------------------

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<DocumentResponse> attachTag(@CurrentUser User user,
                                                      @PathVariable UUID id,
                                                      @PathVariable UUID tagId) {
        tagService.attach(user.getId(), id, tagId);
        return get(user, id);
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<DocumentResponse> detachTag(@CurrentUser User user,
                                                      @PathVariable UUID id,
                                                      @PathVariable UUID tagId) {
        tagService.detach(user.getId(), id, tagId);
        return get(user, id);
    }

    // ------------------------------------------------------------------
    // Retry / delete / bulk delete
    // ------------------------------------------------------------------
    @PostMapping("/{id}/retry")
    public ResponseEntity<DocumentResponse> retry(@CurrentUser User user, @PathVariable UUID id) {
        Document document = documentService.retry(user.getId(), id);
        return ResponseEntity.ok(enrich(List.of(document), user.getId()).get(0));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        documentService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<BulkDeleteResponse> bulkDelete(@CurrentUser User user,
                                                         @Valid @RequestBody BulkDeleteRequest request) {
        List<UUID> deleted = new ArrayList<>();
        List<UUID> skipped = new ArrayList<>();
        for (UUID id : request.getDocumentIds()) {
            try {
                documentService.delete(user.getId(), id);
                deleted.add(id);
            } catch (ApiException e) {
                // not found / not owned: report rather than abort the batch
                skipped.add(id);
            }
        }
        return ResponseEntity.ok(BulkDeleteResponse.of(deleted.size(), skipped));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Document requireOwned(User user, UUID id) {
        return documentRepository.findByIdAndUserIdAndDeletedAtIsNull(id, user.getId())
            .orElseThrow(() -> ApiException.notFound("Document not found"));
    }

    private Sort resolveSort(String sort) {
        String[] parts = sort.split(",");
        String key = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
            ? Sort.Direction.ASC : Sort.Direction.DESC;
        String property = SORT_FIELDS.getOrDefault(key, SORT_FIELDS.getOrDefault(
            key.toLowerCase(), "createdAt"));
        return Sort.by(direction, property);
    }

    private Specification<Document> buildSpec(UUID userId, String q, String status,
                                              UUID tagId, UUID collectionId) {
        Specification<Document> spec = (root, query, cb) ->
            cb.and(
                cb.equal(root.get("user").get("id"), userId),
                cb.isNull(root.get("deletedAt"))
            );

        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("originalFilename")), like),
                cb.equal(root.get("sha256"), q.trim())
            ));
        }

        if (status != null && !status.isBlank()) {
            Document.DocumentStatus docStatus;
            try {
                docStatus = Document.DocumentStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Invalid status: " + status);
            }
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), docStatus));
        }

        if (tagId != null) {
            spec = spec.and((root, query, cb) -> {
                var sub = query.subquery(com.verityai.entity.DocumentTag.class);
                var tagRoot = sub.from(com.verityai.entity.DocumentTag.class);
                sub.select(tagRoot);
                sub.where(cb.equal(tagRoot.get("document"), root),
                          cb.equal(tagRoot.get("tag").get("id"), tagId));
                return cb.exists(sub);
            });
        }

        if (collectionId != null) {
            spec = spec.and((root, query, cb) -> {
                var sub = query.subquery(com.verityai.entity.CollectionDocument.class);
                var cdRoot = sub.from(com.verityai.entity.CollectionDocument.class);
                sub.select(cdRoot);
                sub.where(cb.equal(cdRoot.get("document"), root),
                          cb.equal(cdRoot.get("collection").get("id"), collectionId));
                return cb.exists(sub);
            });
        }

        return spec;
    }

    /**
     * Batch-enriches a page of documents: tags, collections, chunk counts and
     * latest processing stage are each fetched with one query for the whole
     * page (no per-document lookups).
     */
    private List<DocumentResponse> enrich(List<Document> documents, UUID userId) {
        List<UUID> ids = documents.stream().map(Document::getId).collect(Collectors.toList());

        Map<UUID, List<com.verityai.entity.Tag>> tagsByDoc = tagService.tagsForDocuments(userId, ids);
        Map<UUID, List<com.verityai.entity.Collection>> collsByDoc =
            collectionService.collectionsForDocuments(userId, ids);

        Map<UUID, Long> chunkCounts = ids.isEmpty() ? Map.of() :
            chunkRepository.countByDocumentIds(ids).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));

        Map<UUID, DocumentProcessingEvent> latestEvents = processingEventService.latestByDocumentIds(ids);

        List<DocumentResponse> out = new ArrayList<>(documents.size());
        for (Document d : documents) {
            DocumentResponse resp = new DocumentResponse();
            resp.setId(d.getId());
            resp.setOriginalFilename(d.getOriginalFilename());
            resp.setMimeType(d.getMimeType());
            resp.setSizeBytes(d.getSizeBytes());
            resp.setPageCount(d.getPageCount());
            resp.setStatus(DocumentResponse.DocumentStatus.valueOf(d.getStatus().name()));
            resp.setSha256(d.getSha256());
            resp.setErrorCode(d.getErrorCode());
            resp.setErrorMessage(d.getErrorMessage());
            resp.setCreatedAt(d.getCreatedAt());
            resp.setUpdatedAt(d.getUpdatedAt());
            resp.setChunkCount(chunkCounts.getOrDefault(d.getId(), 0L).intValue());
            resp.setSummaryStatus(d.getSummaryStatus() == null ? "NONE" : d.getSummaryStatus().name());
            resp.setSummaryError(d.getSummaryError());

            DocumentProcessingEvent latest = latestEvents.get(d.getId());
            if (latest != null) {
                resp.setProcessingStage(latest.getStage().name());
            }

            List<com.verityai.entity.Tag> tags = tagsByDoc.get(d.getId());
            if (tags != null) {
                resp.setTags(tags.stream().map(t -> {
                    TagResponse ref = new TagResponse();
                    ref.setId(t.getId());
                    ref.setName(t.getName());
                    ref.setColor(t.getColor());
                    return ref;
                }).collect(Collectors.toList()));
            }

            List<com.verityai.entity.Collection> colls = collsByDoc.get(d.getId());
            if (colls != null) {
                resp.setCollections(colls.stream().map(c -> {
                    DocumentResponse.CollectionRef ref = new DocumentResponse.CollectionRef();
                    ref.setId(c.getId());
                    ref.setName(c.getName());
                    return ref;
                }).collect(Collectors.toList()));
            }

            out.add(resp);
        }
        return out;
    }
}
