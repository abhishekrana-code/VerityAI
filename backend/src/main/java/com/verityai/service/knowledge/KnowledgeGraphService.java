package com.verityai.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.verityai.config.BoundedTaskQueue;
import com.verityai.dto.response.KnowledgeConnectionsResponse;
import com.verityai.dto.response.KnowledgeEntityIndexResponse;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.DocumentSummary;
import com.verityai.entity.KnowledgeEntity;
import com.verityai.entity.KnowledgeEntity.EntityType;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.repository.KnowledgeEntityRepository;
import jakarta.annotation.PostConstruct;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final TransactionTemplate transactionTemplate;

    /**
     * Single worker with a bounded queue for asynchronous rebuilds. Startup
     * backfill is the only producer; rejected work is logged and the document's
     * knowledge status stays pending for the next start or an explicit rebuild.
     */
    private BoundedTaskQueue queue;

    /**
     * User-visible explanation stored while a dropped rebuild waits for the
     * startup backfill. It is cleared as soon as a real rebuild starts.
     */
    static final String DEFERRED_MESSAGE =
        "Knowledge graph rebuild was deferred because the background queue is full. "
            + "It will retry automatically on the next server start.";

    @Value("${verityai.knowledge.queue-capacity:100}")
    private int queueCapacity;

    @PostConstruct
    void initQueue() {
        this.queue = new BoundedTaskQueue("Knowledge graph", "knowledge-graph-worker", queueCapacity);
    }

    @Value("${verityai.knowledge.enabled:true}")
    private boolean enabled;

    @Value("${verityai.knowledge.backfill-limit:20}")
    private int backfillLimit;

    @PreDestroy
    void shutdown() {
        if (queue != null) {
            queue.shutdown();
        }
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

        // Replace the old rows only after extraction succeeds.
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

        document.setKnowledgeStatus(Document.KnowledgeStatus.READY);
        document.setKnowledgeError(null);
        document.setKnowledgeGeneratedAt(Instant.now());
        documentRepository.save(document);

        log.info("Indexed entities for document {}: {}{}",
            documentId, saved.size(), extraction.entitiesTruncated() ? " (truncated)" : "");
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

    /**
     * A dropped rebuild must stay eligible for the startup backfill, which scans
     * NONE, PENDING and FAILED. PENDING records that the graph is still owed; a
     * READY graph is never downgraded.
     */
    private void markDeferred(UUID documentId) {
        try {
            documentRepository.findById(documentId).ifPresent(document -> {
                if (document.getKnowledgeStatus() != Document.KnowledgeStatus.READY) {
                    document.setKnowledgeStatus(Document.KnowledgeStatus.PENDING);
                    // Visible reason, not just a silent PENDING: the graph API
                    // returns this as `error` and the graph page shows it.
                    document.setKnowledgeError(DEFERRED_MESSAGE);
                    documentRepository.save(document);
                }
            });
        } catch (Exception e) {
            log.warn("Could not mark knowledge graph for document {} as pending after a dropped job",
                documentId, e);
        }
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

    /** Removes graph rows for a document (soft delete / reprocessing). */
    public void deleteForDocument(UUID documentId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
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
            int queued = 0;
            for (UUID id : batch) {
                if (queue.submit(() -> rebuild(id), id)) {
                    queued++;
                } else {
                    markDeferred(id);
                }
            }
            if (queued < batch.size()) {
                log.warn("Only {} of {} knowledge-graph job(s) were queued at startup; "
                    + "the rest stay pending for the next start",
                    queued, batch.size());
            }
        } catch (Exception e) {
            log.warn("Knowledge-graph backfill skipped: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /**
     * Merged entity index: one row per {@code normalizedName}, so "Alpha Corp"
     * mentioned in three documents is one entry with three document references.
     */
    public KnowledgeEntityIndexResponse entityIndex(User user, UUID documentId, String type,
                                                    String q, int limit) {
        return transactionTemplate.execute(status -> {
            if (documentId != null) {
                documentRepository.findByIdAndUserIdAndDeletedAtIsNull(documentId, user.getId())
                    .orElseThrow(() -> ApiException.notFound("Document not found"));
            }
            int effectiveLimit = Math.min(Math.max(limit, 1), 200);
            // Rows are per document, so fetch more rows than output entries to
            // fill the limit with merged entries rather than one per mention site.
            int rowLimit = Math.min(Math.max(effectiveLimit * 5, 100), 2000);
            EntityType entityType = parseType(type);

            List<KnowledgeEntity> rows = entityRepository.findAll(
                entitySpecification(user.getId(), documentId, entityType, q),
                PageRequest.of(0, rowLimit, Sort.by(Sort.Direction.DESC, "mentionCount")
                    .and(Sort.by(Sort.Direction.ASC, "name")))).getContent();

            Map<String, List<KnowledgeEntity>> grouped = new LinkedHashMap<>();
            for (KnowledgeEntity entity : rows) {
                grouped.computeIfAbsent(entity.getNormalizedName(), key -> new ArrayList<>()).add(entity);
            }

            List<KnowledgeEntityIndexResponse.Item> items = new ArrayList<>();
            for (List<KnowledgeEntity> group : grouped.values()) {
                items.add(toIndexItem(group));
            }
            items.sort(Comparator.comparingInt(KnowledgeEntityIndexResponse.Item::getMentionCount)
                .reversed()
                .thenComparing(KnowledgeEntityIndexResponse.Item::getName, String.CASE_INSENSITIVE_ORDER));

            boolean truncated = rows.size() >= rowLimit || items.size() > effectiveLimit;
            if (items.size() > effectiveLimit) {
                items = new ArrayList<>(items.subList(0, effectiveLimit));
            }

            boolean unfiltered = documentId == null && entityType == null && (q == null || q.isBlank());
            KnowledgeEntityIndexResponse response = new KnowledgeEntityIndexResponse();
            response.setTotalEntities(unfiltered
                ? entityRepository.countDistinctNamesByUserId(user.getId())
                : grouped.size());
            response.setReturnedEntities(items.size());
            response.setTruncated(truncated);
            response.setEntities(items);
            return response;
        });
    }

    /** Document pairs that mention the same entity names. */
    public KnowledgeConnectionsResponse connections(User user, int limit) {
        return transactionTemplate.execute(status -> {
            int pairLimit = Math.min(Math.max(limit, 1), 50);
            int rowLimit = 5000;

            List<KnowledgeEntity> rows = entityRepository.findAll(
                (root, query, cb) -> {
                    if (query != null && !Long.class.equals(query.getResultType())) {
                        root.fetch("document", JoinType.LEFT);
                    }
                    return cb.equal(root.get("user").get("id"), user.getId());
                },
                PageRequest.of(0, rowLimit, Sort.by(Sort.Direction.DESC, "mentionCount"))).getContent();

            Map<UUID, String> documentNames = new LinkedHashMap<>();
            Map<String, Set<UUID>> documentsByName = new LinkedHashMap<>();
            Map<String, String> displayNames = new LinkedHashMap<>();
            for (KnowledgeEntity entity : rows) {
                // A shared year or two-letter code says nothing about how two
                // documents relate, so it must not create a connection.
                if (EntityNoiseFilter.isNoise(entity.getName(), entity.getEntityType())) {
                    continue;
                }
                UUID docId = entity.getDocument().getId();
                documentNames.putIfAbsent(docId, entity.getDocument().getOriginalFilename());
                documentsByName.computeIfAbsent(entity.getNormalizedName(), key -> new LinkedHashSet<>()).add(docId);
                displayNames.putIfAbsent(entity.getNormalizedName(), entity.getName());
            }

            Map<String, KnowledgeConnectionsResponse.Connection> pairs = new LinkedHashMap<>();
            for (Map.Entry<String, Set<UUID>> entry : documentsByName.entrySet()) {
                List<UUID> ids = new ArrayList<>(entry.getValue());
                for (int i = 0; i < ids.size(); i++) {
                    for (int j = i + 1; j < ids.size(); j++) {
                        UUID a = ids.get(i);
                        UUID b = ids.get(j);
                        if (a.toString().compareTo(b.toString()) > 0) {
                            UUID swap = a;
                            a = b;
                            b = swap;
                        }
                        String key = a + "|" + b;
                        KnowledgeConnectionsResponse.Connection connection = pairs.get(key);
                        if (connection == null) {
                            // Bound a pathological library rather than build n^2 rows.
                            if (pairs.size() >= 2000) {
                                continue;
                            }
                            connection = new KnowledgeConnectionsResponse.Connection();
                            connection.setDocumentAId(a);
                            connection.setDocumentAName(documentNames.get(a));
                            connection.setDocumentBId(b);
                            connection.setDocumentBName(documentNames.get(b));
                            connection.setSharedEntities(0);
                            connection.setSampleEntities(new ArrayList<>());
                            pairs.put(key, connection);
                        }
                        connection.setSharedEntities(connection.getSharedEntities() + 1);
                        if (connection.getSampleEntities().size() < 5) {
                            connection.getSampleEntities().add(displayNames.get(entry.getKey()));
                        }
                    }
                }
            }

            List<KnowledgeConnectionsResponse.Connection> sorted = new ArrayList<>(pairs.values());
            sorted.sort(Comparator
                .comparingInt(KnowledgeConnectionsResponse.Connection::getSharedEntities).reversed()
                .thenComparing(KnowledgeConnectionsResponse.Connection::getDocumentAName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
            boolean truncated = rows.size() >= rowLimit || sorted.size() > pairLimit;

            KnowledgeConnectionsResponse response = new KnowledgeConnectionsResponse();
            response.setDocumentCount(documentNames.size());
            response.setPairCount(sorted.size());
            response.setTruncated(truncated);
            response.setConnections(sorted.size() > pairLimit
                ? new ArrayList<>(sorted.subList(0, pairLimit)) : sorted);
            return response;
        });
    }

    private KnowledgeEntityIndexResponse.Item toIndexItem(List<KnowledgeEntity> group) {
        List<KnowledgeEntity> ordered = new ArrayList<>(group);
        ordered.sort(Comparator.comparingInt(KnowledgeEntity::getMentionCount).reversed()
            .thenComparing(KnowledgeEntity::getName, String.CASE_INSENSITIVE_ORDER));
        KnowledgeEntity primary = ordered.get(0);

        Map<UUID, KnowledgeEntityIndexResponse.DocumentRef> documents = new LinkedHashMap<>();
        int mentions = 0;
        for (KnowledgeEntity entity : ordered) {
            mentions += entity.getMentionCount();
            UUID documentId = entity.getDocument().getId();
            KnowledgeEntityIndexResponse.DocumentRef ref = documents.get(documentId);
            if (ref == null) {
                ref = new KnowledgeEntityIndexResponse.DocumentRef();
                ref.setId(documentId);
                ref.setName(entity.getDocument().getOriginalFilename());
                ref.setMentionCount(0);
                ref.setFirstPage(entity.getFirstPage());
                documents.put(documentId, ref);
            }
            ref.setMentionCount(ref.getMentionCount() + entity.getMentionCount());
            if (entity.getFirstPage() != null
                    && (ref.getFirstPage() == null || entity.getFirstPage() < ref.getFirstPage())) {
                ref.setFirstPage(entity.getFirstPage());
            }
        }

        KnowledgeEntityIndexResponse.Item item = new KnowledgeEntityIndexResponse.Item();
        item.setNormalizedName(primary.getNormalizedName());
        item.setName(primary.getName());
        item.setType(primary.getEntityType().name());
        item.setMentionCount(mentions);
        item.setDocumentCount(documents.size());
        item.setEvidenceExcerpt(primary.getEvidenceExcerpt());
        item.setDocuments(new ArrayList<>(documents.values()));
        return item;
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
