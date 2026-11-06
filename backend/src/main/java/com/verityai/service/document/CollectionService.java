package com.verityai.service.document;

import com.verityai.entity.Collection;
import com.verityai.entity.CollectionDocument;
import com.verityai.entity.Document;
import com.verityai.exception.ApiException;
import com.verityai.repository.CollectionDocumentRepository;
import com.verityai.repository.CollectionRepository;
import com.verityai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-user document collections (folders). All lookups are scoped by user id;
 * collection membership is a pure join table so ordering stays in the
 * collections table (updated_at trigger from V4).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CollectionDocumentRepository collectionDocumentRepository;
    private final DocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public List<Collection> list(UUID userId) {
        return collectionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** Collections containing each of the given documents (one query, no N+1). */
    @Transactional(readOnly = true)
    public Map<UUID, List<Collection>> collectionsForDocuments(UUID userId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Collection>> result = new java.util.HashMap<>();
        for (Object[] row : collectionRepository.findMembershipByDocumentIds(userId, documentIds)) {
            result.computeIfAbsent((UUID) row[0], k -> new java.util.ArrayList<>())
                .add((Collection) row[1]);
        }
        return result;
    }

    @Transactional
    public Collection create(UUID userId, String name, String description) {
        String normalized = normalizeName(name, 100);
        if (collectionRepository.existsByUserIdAndNameIgnoreCase(userId, normalized)) {
            throw ApiException.conflict("Collection already exists: " + normalized);
        }
        Collection collection = new Collection();
        var user = new com.verityai.entity.User();
        user.setId(userId);
        collection.setUser(user);
        collection.setName(normalized);
        collection.setDescription(trimTo(description, 500));
        return collectionRepository.save(collection);
    }

    @Transactional
    public Collection update(UUID userId, UUID collectionId, String name, String description) {
        Collection collection = requireOwned(userId, collectionId);
        if (name != null) {
            String normalized = normalizeName(name, 100);
            if (!collection.getName().equalsIgnoreCase(normalized)
                    && collectionRepository.existsByUserIdAndNameIgnoreCase(userId, normalized)) {
                throw ApiException.conflict("Collection already exists: " + normalized);
            }
            collection.setName(normalized);
        }
        if (description != null) {
            collection.setDescription(trimTo(description, 500));
        }
        return collectionRepository.save(collection);
    }

    @Transactional
    public void delete(UUID userId, UUID collectionId) {
        Collection collection = requireOwned(userId, collectionId);
        // collection_documents rows cascade at the DB level (V4 migration)
        collectionRepository.delete(collection);
    }

    /** Adds documents to a collection; skips duplicates, rejects foreign docs. */
    @Transactional
    public int addDocuments(UUID userId, UUID collectionId, List<UUID> documentIds) {
        Collection collection = requireOwned(userId, collectionId);
        if (documentIds == null || documentIds.isEmpty()) {
            throw ApiException.validation("documentIds is required");
        }
        if (documentIds.size() > 100) {
            throw ApiException.validation("Cannot add more than 100 documents at once");
        }
        List<Document> docs = documentRepository.findAllById(documentIds);
        if (docs.size() != documentIds.size()) {
            throw ApiException.notFound("One or more documents were not found");
        }
        for (Document doc : docs) {
            if (!doc.getUser().getId().equals(userId) || doc.getDeletedAt() != null) {
                throw ApiException.forbidden("Document access denied");
            }
        }
        int added = 0;
        var existing = collectionDocumentRepository.findByCollectionAndUser(collection.getId(), userId).stream()
            .map(cd -> cd.getDocument().getId())
            .collect(Collectors.toSet());
        for (Document doc : docs) {
            if (existing.contains(doc.getId())) {
                continue;
            }
            CollectionDocument link = new CollectionDocument();
            link.setCollection(collection);
            link.setDocument(doc);
            collectionDocumentRepository.save(link);
            added++;
        }
        return added;
    }

    @Transactional
    public void removeDocument(UUID userId, UUID collectionId, UUID documentId) {
        requireOwned(userId, collectionId);
        collectionDocumentRepository.deleteByCollectionAndDocument(collectionId, documentId);
    }

    /** All documents of a collection, in insertion order. */
    @Transactional(readOnly = true)
    public List<UUID> documentIds(UUID userId, UUID collectionId) {
        requireOwned(userId, collectionId);
        return collectionDocumentRepository.findByCollectionAndUser(collectionId, userId).stream()
            .map(cd -> cd.getDocument().getId())
            .collect(Collectors.toList());
    }

    private Collection requireOwned(UUID userId, UUID collectionId) {
        return collectionRepository.findByIdAndUserId(collectionId, userId)
            .orElseThrow(() -> ApiException.notFound("Collection not found"));
    }

    private static String normalizeName(String name, int max) {
        if (name == null || name.isBlank()) {
            throw ApiException.validation("Name is required");
        }
        String normalized = name.trim();
        if (normalized.length() > max) {
            throw ApiException.validation("Name must be " + max + " characters or fewer");
        }
        return normalized;
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
