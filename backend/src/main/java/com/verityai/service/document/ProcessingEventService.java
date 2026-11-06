package com.verityai.service.document;

import com.verityai.entity.DocumentProcessingEvent;
import com.verityai.entity.DocumentProcessingEvent.Stage;
import com.verityai.repository.DocumentProcessingEventRepository;
import com.verityai.repository.DocumentRepository;
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
 * Persistent, append-only processing history for documents.
 *
 * <p>Unlike in-memory task state, every stage transition is written to the
 * {@code document_processing_events} table, so the processing center survives
 * restarts and shows an honest timeline (attempt, stage, error, timestamp).</p>
 *
 * <p>Attempt semantics: an attempt starts at {@code QUEUED}. The first run of a
 * freshly uploaded document is attempt 1 (the {@code UPLOADED} event shares it);
 * every subsequent run (retry, restart recovery) increments the attempt.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessingEventService {

    private final DocumentProcessingEventRepository eventRepository;
    private final DocumentRepository documentRepository;

    /** Records file acceptance for a new document (attempt 1). */
    @Transactional
    public void recordUploaded(UUID documentId) {
        save(documentId, 1, Stage.UPLOADED, null, null);
    }

    /**
     * Starts a new processing attempt: records {@code QUEUED} and returns the
     * attempt number so later stages of the same run can be tagged with it.
     */
    @Transactional
    public int beginAttempt(UUID documentId) {
        int attempt = (int) eventRepository.countByDocumentIdAndStage(documentId, Stage.QUEUED) + 1;
        save(documentId, attempt, Stage.QUEUED, null, null);
        return attempt;
    }

    /** Records a stage transition within the current attempt. */
    @Transactional
    public void record(UUID documentId, int attempt, Stage stage) {
        save(documentId, attempt, stage, null, null);
    }

    /** Records a terminal failure for the given attempt. */
    @Transactional
    public void recordFailure(UUID documentId, int attempt, String errorCode, String errorMessage) {
        save(documentId, attempt, Stage.FAILED, errorCode, errorMessage);
    }

    /**
     * The attempt number the next {@link #beginAttempt} would use, without
     * recording anything. Used when a run fails before it can be queued.
     */
    public int nextAttempt(UUID documentId) {
        return (int) eventRepository.countByDocumentIdAndStage(documentId, Stage.QUEUED) + 1;
    }

    private void save(UUID documentId, int attempt, Stage stage, String code, String message) {
        try {
            DocumentProcessingEvent event = new DocumentProcessingEvent();
            event.setDocument(documentRepository.getReferenceById(documentId));
            event.setAttempt(attempt);
            event.setStage(stage);
            event.setErrorCode(truncate(code, 50));
            event.setErrorMessage(truncate(message, 2000));
            eventRepository.save(event);
        } catch (Exception e) {
            // History must never break the pipeline itself.
            log.warn("Failed to record processing event {} for document {}", stage, documentId, e);
        }
    }

    /** Full history for one document, newest first. */
    @Transactional(readOnly = true)
    public List<DocumentProcessingEvent> history(UUID documentId) {
        return eventRepository.findByDocumentIdOrderByCreatedAtDesc(documentId);
    }

    /** Latest event per document for a page of documents (single query). */
    @Transactional(readOnly = true)
    public Map<UUID, DocumentProcessingEvent> latestByDocumentIds(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return eventRepository.findLatestByDocumentIds(documentIds).stream()
            .collect(Collectors.toMap(e -> e.getDocument().getId(), Function.identity(), (a, b) -> a));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
