package com.verityai.service.document;

import com.verityai.entity.Document;
import com.verityai.entity.DocumentTag;
import com.verityai.entity.Tag;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentTagRepository;
import com.verityai.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-user document tags. All lookups are scoped by user id so one user can
 * never read or attach another user's tag.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TagService {

    private final TagRepository tagRepository;
    private final DocumentTagRepository documentTagRepository;
    private final DocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public List<Tag> list(UUID userId) {
        return tagRepository.findByUserIdOrderByNameAsc(userId);
    }

    /** Tag usage counts for a set of documents in one query (no N+1). */
    @Transactional(readOnly = true)
    public Map<UUID, List<Tag>> tagsForDocuments(UUID userId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return tagRepository.findTagsByDocumentIds(userId, documentIds).stream()
            .collect(Collectors.groupingBy(
                row -> (UUID) row[1],
                Collectors.mapping(row -> (Tag) row[0], Collectors.toList())));
    }

    @Transactional
    public Tag create(UUID userId, String name, String color) {
        String normalized = normalizeName(name);
        if (tagRepository.existsByUserIdAndNameIgnoreCase(userId, normalized)) {
            throw ApiException.conflict("Tag already exists: " + normalized);
        }
        Tag tag = new Tag();
        var user = new com.verityai.entity.User();
        user.setId(userId);
        tag.setUser(user);
        tag.setName(normalized);
        tag.setColor(color);
        return tagRepository.save(tag);
    }

    @Transactional
    public void rename(UUID userId, UUID tagId, String name, String color) {
        Tag tag = requireOwned(userId, tagId);
        String normalized = normalizeName(name);
        if (!tag.getName().equalsIgnoreCase(normalized)
                && tagRepository.existsByUserIdAndNameIgnoreCase(userId, normalized)) {
            throw ApiException.conflict("Tag already exists: " + normalized);
        }
        tag.setName(normalized);
        if (color != null) {
            tag.setColor(color);
        }
        tagRepository.save(tag);
    }

    @Transactional
    public void delete(UUID userId, UUID tagId) {
        Tag tag = requireOwned(userId, tagId);
        // document_tags rows cascade at the DB level (V4 migration)
        tagRepository.delete(tag);
    }

    /** Attaches an existing tag to a document (idempotent). */
    @Transactional
    public void attach(UUID userId, UUID documentId, UUID tagId) {
        Document doc = requireOwnedDocument(userId, documentId);
        Tag tag = requireOwned(userId, tagId);
        if (documentTagRepository.existsByDocumentIdAndTagId(doc.getId(), tag.getId())) {
            return;
        }
        DocumentTag link = new DocumentTag();
        link.setDocument(doc);
        link.setTag(tag);
        documentTagRepository.save(link);
    }

    /** Detaches a tag from a document (idempotent). */
    @Transactional
    public void detach(UUID userId, UUID documentId, UUID tagId) {
        requireOwnedDocument(userId, documentId);
        requireOwned(userId, tagId);
        documentTagRepository.deleteByDocumentAndTag(documentId, tagId);
    }

    private Tag requireOwned(UUID userId, UUID tagId) {
        return tagRepository.findByIdAndUserId(tagId, userId)
            .orElseThrow(() -> ApiException.notFound("Tag not found"));
    }

    private Document requireOwnedDocument(UUID userId, UUID documentId) {
        return documentRepository.findByIdAndUserIdAndDeletedAtIsNull(documentId, userId)
            .orElseThrow(() -> ApiException.notFound("Document not found"));
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.validation("Tag name is required");
        }
        String normalized = name.trim();
        if (normalized.length() > 50) {
            throw ApiException.validation("Tag name must be 50 characters or fewer");
        }
        return normalized;
    }
}
