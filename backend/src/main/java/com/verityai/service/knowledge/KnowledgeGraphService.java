package com.verityai.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.verityai.dto.response.KnowledgeGraphRebuildResponse;
import com.verityai.dto.response.KnowledgeGraphResponse;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.DocumentSummary;
import com.verityai.entity.KnowledgeEntity;
import com.verityai.entity.KnowledgeEntity.EntityType;
import com.verityai.entity.KnowledgeRelation;
import com.verityai.entity.KnowledgeRelation.RelationType;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.repository.KnowledgeEntityRepository;
import com.verityai.repository.KnowledgeRelationRepository;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Builds and reads the PostgreSQL knowledge graph (Phase 3, Feature 7).
 *
 * <p>Extraction is deterministic and bounded; the optional summary ENTITIES
 * section only enriches entity types. Graph builds run in their own short
 * transactions so a failure marks the document FAILED without rolling back the
 * document-processing pipeline. Startup backfill is bounded and asynchronous,
 * mirroring the summary/embedding backfills.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentSummaryRepository summaryRepository;
    private final KnowledgeEntityRepository entityRepository;
    private final KnowledgeRelationRepository relationRepository;
    private final TransactionTemplate transactionTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-graph-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${verityai.knowledge.enabled:true}")
    private boolean enabled;

    @Value("${verityai.knowledge.backfill-limit:20}")
    private int backfillLimit;

    @Value("${verityai.knowledge.rebuild-max-documents:10}")
    private int rebuildMaxDocuments;

    @Value("${verityai.knowledge.max-nodes:300}")
    private int maxNodes;

    @Value("${verityai.knowledge.max-edges:4000}")
    private int maxEdges;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /**
     * Rebuilds one document's graph. Never throws: a failure is persisted as
     * {@code knowledgeStatus=FAILED} and reported in the log, so document
     * processing is never broken by graph extraction.
     */
    public boolean rebuild(UUID documentId) {
        if (!enabled) {
            return false;
        }
        try {
            Boolean result = transactionTemplate.execute(status -> doRebuild(documentId));
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Knowledge graph build failed for document {}: {}", documentId, e.getMessage());
            markFailed(documentId, e);
            return false;
        }
    }

    private boolean doRebuild(UUID documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null || document.getDeletedAt() != null
                || document.getStatus() != Document.DocumentStatus.READY) {
            return false;
        }

        document.setKnowledgeStatus(Document.KnowledgeStatus.PENDING);
        document.setKnowledgeError(null);
        documentRepository.save(document);

        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        if (chunks.isEmpty()) {
            document.setKnowledgeStatus(Document.KnowledgeStatus.READY);
            document.setKnowledgeGeneratedAt(Instant.now());
            documentRepository.save(document);
            relationRepository.deleteByDocumentId(documentId);
            entityRepository.deleteByDocumentId(documentId);
            return true;
        }

        List<KnowledgeExtractor.ChunkText> chunkTexts = chunks.stream()
            .map(c -> new KnowledgeExtractor.ChunkText(
                c.getPageNumber() == null ? 0 : c.getPageNumber(),
                c.getChunkIndex() == null ? 0 : c.getChunkIndex(),
                c.getContent()))
            .collect(Collectors.toList());

        List<KnowledgeExtractor.TypedName> summaryEntities = loadSummaryEntities(documentId);
        KnowledgeExtractor.Extraction extraction =
            KnowledgeExtractor.extract(chunkTexts, summaryEntities);

        // Replace the old graph only after extraction succeeds.
        relationRepository.deleteByDocumentId(documentId);
        entityRepository.deleteByDocumentId(documentId);

        Map<String, KnowledgeEntity> saved = new HashMap<>();
        for (KnowledgeExtractor.ExtractedEntity candidate : extraction.entities()) {
            KnowledgeEntity entity = new KnowledgeEntity();
            entity.setUser(document.getUser());
            entity.setDocument(document);
            entity.setName(candidate.name());
            entity.setNormalizedName(candidate.normalizedName());
            entity.setEntityType(candidate.type());
            entity.setMentionCount(candidate.mentionCount());
            entity.setFirstPage(candidate.firstPage());
            entity.setEvidenceExcerpt(candidate.evidenceExcerpt());
            saved.put(candidate.normalizedName(), entityRepository.save(entity));
        }

        List<KnowledgeRelation> relations = new ArrayList<>();
        for (KnowledgeExtractor.ExtractedRelation candidate : extraction.relations()) {
            KnowledgeEntity source = saved.get(candidate.sourceNormalized());
            KnowledgeEntity target = saved.get(candidate.targetNormalized());
            if (source == null || target == null || source.getId().equals(target.getId())) {
                continue;
            }
            // Canonical order is enforced by the DB check constraint.
            if (source.getId().toString().compareTo(target.getId().toString()) > 0) {
                KnowledgeEntity swap = source;
                source = target;
                target = swap;
            }
            KnowledgeRelation relation = new KnowledgeRelation();
            relation.setUser(document.getUser());
            relation.setDocument(document);
            relation.setSourceEntity(source);
            relation.setTargetEntity(target);
            relation.setRelationType(RelationType.CO_OCCURS);
            relation.setWeight(candidate.weight());
            relation.setFirstPage(candidate.firstPage());
            relation.setEvidenceExcerpt(candidate.evidenceExcerpt());
            relations.add(relation);
        }
        relationRepository.saveAll(relations);

        document.setKnowledgeStatus(Document.KnowledgeStatus.READY);
        document.setKnowledgeError(null);
        document.setKnowledgeGeneratedAt(Instant.now());
        documentRepository.save(document);

        log.info("Built knowledge graph for document {}: {} entities, {} relations{}",
            documentId, saved.size(), relations.size(),
            extraction.entitiesTruncated() || extraction.relationsTruncated() ? " (truncated)" : "");
        return true;
    }

    private List<KnowledgeExtractor.TypedName> loadSummaryEntities(UUID documentId) {
        return summaryRepository.findByDocumentIdAndKind(documentId, DocumentSummary.Kind.ENTITIES)
            .map(DocumentSummary::getContent)
            .map(content -> {
                List<KnowledgeExtractor.TypedName> names = new ArrayList<>();
                JsonNode arr = content.get("entities");
                if (arr != null && arr.isArray()) {
                    for (JsonNode item : arr) {
                        String name = item.path("name").asText("");
                        String type = item.path("type").asText("");
                        if (!name.isBlank()) {
                            names.add(new KnowledgeExtractor.TypedName(name, type));
                        }
                    }
                }
                return names;
            })
            .orElse(List.of());
    }

    private void markFailed(UUID documentId, Exception error) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                documentRepository.findById(documentId).ifPresent(document -> {
                    document.setKnowledgeStatus(Document.KnowledgeStatus.FAILED);
                    document.setKnowledgeError(truncate(
                        "Knowledge graph build failed: " + safeMessage(error), 300));
                    documentRepository.save(document);
                }));
        } catch (Exception e) {
            log.warn("Could not persist knowledge-graph failure for document {}", documentId, e);
        }
    }

    /**
     * Rebuilds the given documents (or all READY documents for the user when
     * the list is empty). Bounded so one request cannot rebuild a whole
     * library synchronously.
     */
    public KnowledgeGraphRebuildResponse rebuildForUser(UUID userId, List<UUID> documentIds) {
        KnowledgeGraphRebuildResponse response = new KnowledgeGraphRebuildResponse();
        List<UUID> targets;

        if (documentIds == null || documentIds.isEmpty()) {
            targets = documentRepository.findReadyDocumentIdsForUser(
                userId, Document.DocumentStatus.READY,
                PageRequest.of(0, Math.max(1, rebuildMaxDocuments)));
        } else {
            targets = documentIds.stream().distinct().limit(rebuildMaxDocuments).toList();
        }
        response.setRequested(targets.size());

        for (UUID documentId : targets) {
            Document document = documentRepository
                .findByIdAndUserIdAndDeletedAtIsNull(documentId, userId)
                .orElse(null);
            if (document == null || document.getStatus() != Document.DocumentStatus.READY) {
                response.setSkipped(response.getSkipped() + 1);
                continue;
            }
            if (rebuild(documentId)) {
                response.setRebuilt(response.getRebuilt() + 1);
                response.getRebuiltDocumentIds().add(documentId);
            } else {
                response.getErrors().add("Could not rebuild document " + documentId);
            }
        }
        return response;
    }

    /** Removes graph rows for a document (soft delete / reprocessing). */
    public void deleteForDocument(UUID documentId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                relationRepository.deleteByDocumentId(documentId);
                entityRepository.deleteByDocumentId(documentId);
                documentRepository.findById(documentId).ifPresent(document -> {
                    document.setKnowledgeStatus(Document.KnowledgeStatus.NONE);
                    document.setKnowledgeError(null);
                    document.setKnowledgeGeneratedAt(null);
                    documentRepository.save(document);
                });
            });
        } catch (Exception e) {
            log.warn("Could not clear knowledge graph for document {}", documentId, e);
        }
    }

    /** Bounded asynchronous backfill for READY documents without a graph. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillPendingGraphs() {
        if (!enabled) {
            return;
        }
        try {
            List<UUID> pending = documentRepository.findDocumentsPendingKnowledge(
                Document.DocumentStatus.READY,
                List.of(Document.KnowledgeStatus.NONE,
                        Document.KnowledgeStatus.PENDING,
                        Document.KnowledgeStatus.FAILED));
            if (pending.isEmpty()) {
                return;
            }
            List<UUID> batch = pending.size() > backfillLimit
                ? pending.subList(0, backfillLimit) : pending;
            log.info("Queuing {} knowledge-graph job(s) at startup ({} total pending)",
                batch.size(), pending.size());
            for (UUID id : batch) {
                executor.submit(() -> rebuild(id));
            }
        } catch (Exception e) {
            log.warn("Knowledge-graph backfill skipped: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    public KnowledgeGraphResponse graph(User user, UUID documentId, String type,
                                        String q, int limit) {
        return transactionTemplate.execute(status -> {
            Document document = null;
            if (documentId != null) {
                document = documentRepository.findByIdAndUserIdAndDeletedAtIsNull(documentId, user.getId())
                    .orElseThrow(() -> ApiException.notFound("Document not found"));
            }

            int effectiveLimit = Math.min(Math.max(limit, 1), Math.max(1, maxNodes));
            EntityType entityType = parseType(type);

            Specification<KnowledgeEntity> entitySpec = entitySpecification(
                user.getId(), documentId, entityType, q);
            List<KnowledgeEntity> entities = entityRepository.findAll(entitySpec,
                PageRequest.of(0, effectiveLimit + 1,
                    Sort.by(Sort.Direction.DESC, "mentionCount")
                        .and(Sort.by(Sort.Direction.ASC, "name")))).getContent();

            boolean truncated = entities.size() > effectiveLimit;
            if (truncated) {
                entities = entities.subList(0, effectiveLimit);
            }

            Set<UUID> nodeIds = entities.stream().map(KnowledgeEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

            List<KnowledgeRelation> relations = nodeIds.isEmpty() ? List.of()
                : relationRepository.findAll(relationSpecification(user.getId(), documentId, nodeIds),
                    PageRequest.of(0, Math.max(1, maxEdges),
                        Sort.by(Sort.Direction.DESC, "weight"))).getContent();

            long entityCount = documentId == null
                ? entityRepository.countByUserId(user.getId())
                : entityRepository.countByUserIdAndDocumentId(user.getId(), documentId);
            long relationCount = documentId == null
                ? relationRepository.countByUserId(user.getId())
                : relationRepository.countByUserIdAndDocumentId(user.getId(), documentId);

            KnowledgeGraphResponse response = new KnowledgeGraphResponse();
            response.setDocumentId(documentId);
            response.setDocumentName(document == null ? null : document.getOriginalFilename());
            response.setScope(documentId == null ? "LIBRARY" : "DOCUMENT");
            response.setStatus(document != null
                ? document.getKnowledgeStatus().name()
                : (entityCount == 0 ? "NONE" : "READY"));
            response.setError(document == null ? null : document.getKnowledgeError());
            response.setGeneratedAt(document != null
                ? document.getKnowledgeGeneratedAt()
                : documentRepository.findLatestKnowledgeGeneratedAtForUser(user.getId()));
            response.setEntityCount(entityCount);
            response.setRelationCount(relationCount);
            response.setDocumentCount(documentId != null
                ? (entityCount > 0 ? 1 : 0)
                : (int) entityRepository.countDistinctDocumentsByUserId(user.getId()));
            response.setReturnedEntities(entities.size());
            response.setTruncated(truncated);
            response.setNodes(entities.stream().map(this::toNode).toList());
            response.setEdges(relations.stream().map(this::toEdge).toList());
            return response;
        });
    }

    private Specification<KnowledgeEntity> entitySpecification(UUID userId, UUID documentId,
                                                               EntityType type, String q) {
        return (root, query, cb) -> {
            if (query != null && !Long.class.equals(query.getResultType())) {
                root.fetch("document", JoinType.LEFT);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));
            if (documentId != null) {
                predicates.add(cb.equal(root.get("document").get("id"), documentId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("entityType"), type));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("name")), like));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Specification<KnowledgeRelation> relationSpecification(UUID userId, UUID documentId,
                                                                    Collection<UUID> nodeIds) {
        return (root, query, cb) -> {
            boolean countQuery = query != null && Long.class.equals(query.getResultType());
            Join<Object, Object> source;
            Join<Object, Object> target;
            if (countQuery) {
                source = root.join("sourceEntity");
                target = root.join("targetEntity");
            } else {
                // Fetch the endpoints and document so mapping edges does not
                // issue one query per association.
                source = (Join<Object, Object>) root.fetch("sourceEntity", JoinType.INNER);
                target = (Join<Object, Object>) root.fetch("targetEntity", JoinType.INNER);
                root.fetch("document", JoinType.LEFT);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));
            if (documentId != null) {
                predicates.add(cb.equal(root.get("document").get("id"), documentId));
            }
            predicates.add(source.get("id").in(nodeIds));
            predicates.add(target.get("id").in(nodeIds));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private KnowledgeGraphResponse.Node toNode(KnowledgeEntity entity) {
        KnowledgeGraphResponse.Node node = new KnowledgeGraphResponse.Node();
        node.setId(entity.getId());
        node.setName(entity.getName());
        node.setType(entity.getEntityType().name());
        node.setMentionCount(entity.getMentionCount());
        node.setDocumentId(entity.getDocument().getId());
        node.setDocumentName(entity.getDocument().getOriginalFilename());
        node.setFirstPage(entity.getFirstPage());
        node.setEvidenceExcerpt(entity.getEvidenceExcerpt());
        return node;
    }

    private KnowledgeGraphResponse.Edge toEdge(KnowledgeRelation relation) {
        KnowledgeGraphResponse.Edge edge = new KnowledgeGraphResponse.Edge();
        edge.setId(relation.getId());
        edge.setSource(relation.getSourceEntity().getId());
        edge.setTarget(relation.getTargetEntity().getId());
        edge.setRelationType(relation.getRelationType().name());
        edge.setWeight(relation.getWeight());
        edge.setDocumentId(relation.getDocument().getId());
        edge.setFirstPage(relation.getFirstPage());
        edge.setEvidenceExcerpt(relation.getEvidenceExcerpt());
        return edge;
    }

    private static EntityType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Invalid entity type: " + value);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
