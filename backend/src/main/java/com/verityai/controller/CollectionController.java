package com.verityai.controller;

import com.verityai.dto.request.AddDocumentsToCollectionRequest;
import com.verityai.dto.request.CreateCollectionRequest;
import com.verityai.dto.request.UpdateCollectionRequest;
import com.verityai.dto.response.CollectionResponse;
import com.verityai.entity.Collection;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.CollectionDocumentRepository;
import com.verityai.security.CurrentUser;
import com.verityai.service.document.CollectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;
    private final CollectionDocumentRepository collectionDocumentRepository;

    @GetMapping
    public ResponseEntity<List<CollectionResponse>> list(@CurrentUser User user) {
        List<Collection> collections = collectionService.list(user.getId());
        // One grouped query for counts across all collections (no N+1)
        var counts = collectionDocumentRepository.countByUser(user.getId()).stream()
            .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
        return ResponseEntity.ok(collections.stream().map(c -> toResponse(c, counts)).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CollectionResponse> get(@CurrentUser User user, @PathVariable UUID id) {
        CollectionResponse resp = list(user).getBody().stream()
            .filter(c -> c.getId().equals(id))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Collection not found"));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{id}/documents")
    public ResponseEntity<Map<String, List<UUID>>> documents(@CurrentUser User user, @PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("documentIds", collectionService.documentIds(user.getId(), id)));
    }

    @PostMapping
    public ResponseEntity<CollectionResponse> create(@CurrentUser User user,
                                                     @Valid @RequestBody CreateCollectionRequest request) {
        Collection collection = collectionService.create(user.getId(), request.getName(), request.getDescription());
        return ResponseEntity.ok(toResponse(collection, Map.of()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CollectionResponse> update(@CurrentUser User user,
                                                     @PathVariable UUID id,
                                                     @Valid @RequestBody UpdateCollectionRequest request) {
        Collection collection = collectionService.update(user.getId(), id, request.getName(), request.getDescription());
        return ResponseEntity.ok(toResponse(collection, Map.of()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        collectionService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<CollectionResponse> addDocuments(@CurrentUser User user,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody AddDocumentsToCollectionRequest request) {
        collectionService.addDocuments(user.getId(), id, request.getDocumentIds());
        return get(user, id);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Void> removeDocument(@CurrentUser User user,
                                               @PathVariable UUID id,
                                               @PathVariable UUID documentId) {
        collectionService.removeDocument(user.getId(), id, documentId);
        return ResponseEntity.noContent().build();
    }

    private CollectionResponse toResponse(Collection c, Map<UUID, Long> counts) {
        CollectionResponse resp = new CollectionResponse();
        resp.setId(c.getId());
        resp.setName(c.getName());
        resp.setDescription(c.getDescription());
        resp.setCreatedAt(c.getCreatedAt());
        resp.setUpdatedAt(c.getUpdatedAt());
        resp.setDocumentCount(counts.getOrDefault(c.getId(), 0L));
        return resp;
    }
}
