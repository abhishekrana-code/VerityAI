package com.verityai.service.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.DocumentSummary;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import com.verityai.service.ai.LlmService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates and persists AI summaries for documents.
 *
 * <p>Honesty rules encoded here:</p>
 * <ul>
 *   <li>Everything stored is produced by the LLM from the document's own
 *       extracted text — nothing is fabricated server-side.</li>
 *   <li>Extractive kinds (entities, dates/stats) are stored with
 *       {@code source=EXTRACTED}; interpretive kinds with
 *       {@code source=GENERATED}, so the UI can label them differently.</li>
 *   <li>Key findings carry a per-item FACT / INTERPRETATION tag produced by
 *       the model.</li>
 *   <li>Failures are surfaced as {@code summaryStatus=FAILED} with a real
 *       error message — never a silent empty state.</li>
 * </ul>
 *
 * <p>Long documents are summarized hierarchically: the text is split into
 * segments, each segment is condensed by one bounded LLM call, and a final
 * pass runs over the condensed digests. Segment count is capped so a single
 * document can never trigger an unbounded number of provider calls.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SummaryService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentSummaryRepository summaryRepository;
    private final LlmService llmService;
    private final com.verityai.service.knowledge.KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper;

    /** Single worker: summaries are generated serially, one document at a time. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "summary-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${verityai.summary.enabled:true}")
    private boolean enabled;

    /** Below this many characters the whole document goes to the LLM in one call. */
    @Value("${verityai.summary.single-pass-chars:48000}")
    private int singlePassChars;

    /** Hard cap on segment (map) calls per document. */
    @Value("${verityai.summary.max-segments:8}")
    private int maxSegments;

    @Value("${verityai.summary.max-tokens:8000}")
    private int maxTokens;

    /**
     * Token budget for segment condensation. Kept generous because
     * reasoning models (gpt-oss) routinely spend 2000+ tokens reasoning
     * before emitting any content — a tight budget yields an empty digest
     * (observed at 900 and 2500).
     */
    @Value("${verityai.summary.segment-max-tokens:8000}")
    private int segmentMaxTokens;

    /** Optional dedicated summary model; empty falls back to the chat model. */
    @Value("${verityai.summary.model:}")
    private String summaryModelSetting;

    private String summaryModel() {
        return summaryModelSetting == null || summaryModelSetting.isBlank()
            ? model : summaryModelSetting;
    }

    /** Cap on automatic backfill documents per startup. */
    @Value("${verityai.summary.backfill-limit:20}")
    private int backfillLimit;

    @Value("${verityai.groq.model:openai/gpt-oss-20b}")
    private String model;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Queues summary generation for a document (no-op when disabled or the
     * document is gone). Safe to call from async processing threads.
     */
    public void schedule(UUID documentId) {
        if (!enabled) {
            return;
        }
        executor.submit(() -> {
            try {
                generate(documentId);
            } catch (Exception e) {
                log.error("Summary generation failed for document {}", documentId, e);
                markFailed(documentId, "Summary generation failed: " + safeMessage(e));
            }
        });
    }

    /**
     * Explicit regeneration requested by the user. Clears previous summaries,
     * marks the document PENDING and queues a fresh run after commit (so the
     * worker never races the delete transaction).
     */
    @Transactional
    public void regenerate(UUID documentId) {
        summaryRepository.deleteByDocumentId(documentId);
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setSummaryStatus(Document.SummaryStatus.PENDING);
        doc.setSummaryError(null);
        documentRepository.save(doc);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(documentId);
                }
            });
        } else {
            schedule(documentId);
        }
    }

    /**
     * Clears summaries without scheduling a new run. Used when a document is
     * reprocessed: the fresh run schedules its own summaries once indexing
     * completes, so there is no window where stale content is on display.
     */
    @Transactional
    public void regenerateReset(UUID documentId) {
        summaryRepository.deleteByDocumentId(documentId);
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setSummaryStatus(Document.SummaryStatus.NONE);
            doc.setSummaryError(null);
            documentRepository.save(doc);
        });
    }

    /** Retry failed/stuck summaries once at startup (bounded batch). */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillPendingSummaries() {
        if (!enabled) {
            return;
        }
        try {
            List<UUID> pending = summaryRepository.findDocumentsPendingSummary();
            if (pending.isEmpty()) {
                return;
            }
            List<UUID> batch = pending.size() > backfillLimit
                ? pending.subList(0, backfillLimit) : pending;
            log.info("Queuing {} document summary job(s) at startup ({} total pending)",
                batch.size(), pending.size());
            for (UUID id : batch) {
                schedule(id);
            }
        } catch (Exception e) {
            log.warn("Summary backfill skipped: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Generation pipeline
    // ------------------------------------------------------------------

    private void generate(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || doc.getDeletedAt() != null
                || doc.getStatus() != Document.DocumentStatus.READY) {
            return;
        }

        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        String text = joinChunks(chunks);
        if (text.isBlank()) {
            markFailed(documentId, "No extractable text is available to summarize");
            return;
        }

        markPending(documentId);

        try {
            boolean singlePass = text.length() <= singlePassChars;
            String basis = singlePass ? text : condense(text);
            log.info("Generating summaries for document {}: source {} chars, {} (basis {} chars)",
                documentId, text.length(), singlePass ? "single-pass" : "hierarchical", basis.length());

            JsonNode sections = requestSections(basis);
            persist(documentId, sections, basis.length() <= text.length() ? text : basis);
            markReady(documentId);
            // The summary model classifies entities (PERSON/ORGANIZATION/…).
            // Rebuild the graph so those richer types replace pattern-only ones.
            knowledgeGraphService.rebuild(documentId);
            log.info("Generated summaries for document {} ({} chars of source text)",
                documentId, text.length());
        } catch (Exception e) {
            log.error("Summary generation failed for document {}", documentId, e);
            markFailed(documentId, "Summary generation failed: " + safeMessage(e));
        }
    }

    private String joinChunks(List<DocumentChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        int lastPage = -1;
        for (DocumentChunk chunk : chunks) {
            if (chunk.getPageNumber() != null && chunk.getPageNumber() != lastPage) {
                lastPage = chunk.getPageNumber();
                sb.append("\n[Page ").append(lastPage).append("]\n");
            }
            sb.append(chunk.getContent()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Hierarchical step: split long text into bounded segments, condense each
     * with one LLM call, and return the joined digests for the final pass.
     */
    private String condense(String text) {
        int segmentSize = (int) Math.ceil((double) text.length() / maxSegments);
        List<String> digests = new ArrayList<>();
        for (int start = 0; start < text.length(); start += segmentSize) {
            int end = Math.min(start + segmentSize, text.length());
            String segment = text.substring(start, end);

            LlmService.CompletionResponse resp = llmService.complete(new LlmService.CompletionRequest(
                SEGMENT_SYSTEM,
                "Condense the following document segment. Preserve every concrete fact, name, "
                    + "date, number and statistic. Do not add information that is not present.\n\n"
                    + "SEGMENT:\n" + segment,
                summaryModel(),
                segmentMaxTokens
            ));
            String digest = resp.content() == null ? "" : resp.content().trim();
            if (digest.isEmpty()) {
                // Reasoning models can spend the whole token budget on reasoning and
                // emit no content. Never lose the text: fall back to the raw segment.
                log.warn("Condense produced an empty digest for segment {}-{}; keeping raw text", start, end);
                digest = segment.length() > 4000 ? segment.substring(0, 4000) + " …" : segment;
            }
            digests.add(digest);
            log.info("Condense segment {}-{} produced {} chars", start, end, digest.length());
        }
        return String.join("\n\n---\n\n", digests);
    }

    /** Single structured pass producing all six summary sections as JSON. */
    private JsonNode requestSections(String basis) {
        String userPrompt = SECTIONS_PROMPT + "\n\nDOCUMENT:\n" + basis;

        String raw = llmService.complete(new LlmService.CompletionRequest(
            SECTIONS_SYSTEM, userPrompt, summaryModel(), maxTokens)).content();

        log.info("Summary sections raw response: {} chars, starts with: {}",
            raw == null ? -1 : raw.length(), raw == null ? "null" : truncate(raw, 300));

        JsonNode sections;
        try {
            sections = parseJsonObject(raw);
        } catch (Exception first) {
            // One corrective retry: models occasionally wrap JSON in prose/fences.
            log.warn("Summary JSON parse failed, retrying once: {}", first.getMessage());
            String retryRaw = llmService.complete(new LlmService.CompletionRequest(
                SECTIONS_SYSTEM,
                userPrompt + "\n\nYour previous answer was not valid JSON. "
                    + "Reply again with ONLY the JSON object, no commentary.",
                summaryModel(), maxTokens)).content();
            sections = parseJsonObject(retryRaw);
        }

        if (isEmptySections(sections)) {
            // An all-empty result means the model saw nothing usable (or spent its
            // budget on reasoning). Retry once with an explicit corrective nudge.
            log.warn("Summary sections came back empty; retrying once with corrective prompt");
            String retryRaw = llmService.complete(new LlmService.CompletionRequest(
                SECTIONS_SYSTEM,
                userPrompt + "\n\nYour previous answer contained only empty values. "
                    + "The document text above is real — analyze it and fill in every field "
                    + "that the text supports. Reply with ONLY the JSON object.",
                summaryModel(), maxTokens)).content();
            sections = parseJsonObject(retryRaw);
            if (isEmptySections(sections)) {
                // Surface an honest failure instead of persisting empty content as READY.
                throw new IllegalStateException(
                    "The model returned an empty summary twice — no content could be extracted");
            }
        }
        return sections;
    }

    /** True when every field of the sections object is blank or an empty array. */
    private static boolean isEmptySections(JsonNode sections) {
        if (sections == null || !sections.isObject()) return true;
        JsonNode summary = sections.get("executive_summary");
        boolean summaryBlank = summary == null || summary.isNull() || summary.asText("").isBlank();
        boolean anyArrayItem = false;
        for (String field : new String[]{"key_findings", "entities", "dates_and_stats", "topics", "suggested_questions"}) {
            JsonNode arr = sections.get(field);
            if (arr != null && arr.isArray() && arr.size() > 0) {
                anyArrayItem = true;
                break;
            }
        }
        return summaryBlank && !anyArrayItem;
    }

    private JsonNode parseJsonObject(String raw) {
        String s = raw == null ? "" : raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("No JSON object found in model output");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(s.substring(start, end + 1));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Model output is not valid JSON: " + e.getOriginalMessage());
        }
        if (!node.isObject()) {
            throw new IllegalStateException("Model output is not a JSON object");
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persist(UUID documentId, JsonNode sections, String sourceText) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }

        List<DocumentSummary> rows = new ArrayList<>();
        rows.add(row(doc, DocumentSummary.Kind.EXECUTIVE_SUMMARY, DocumentSummary.Source.GENERATED,
            obj("summary", text(sections, "executive_summary"))));
        rows.add(row(doc, DocumentSummary.Kind.KEY_FINDINGS, DocumentSummary.Source.GENERATED,
            findings(sections)));
        rows.add(row(doc, DocumentSummary.Kind.ENTITIES, DocumentSummary.Source.EXTRACTED,
            arr("entities", sections, "entities", "name", "type")));
        rows.add(row(doc, DocumentSummary.Kind.DATES_AND_STATS, DocumentSummary.Source.EXTRACTED,
            arr("items", sections, "dates_and_stats", "text", null)));
        rows.add(row(doc, DocumentSummary.Kind.TOPICS, DocumentSummary.Source.GENERATED,
            strArray("topics", sections, "topics")));
        rows.add(row(doc, DocumentSummary.Kind.SUGGESTED_QUESTIONS, DocumentSummary.Source.GENERATED,
            strArray("questions", sections, "suggested_questions")));

        summaryRepository.deleteByDocumentId(documentId);
        summaryRepository.saveAll(rows);
        log.debug("Persisted {} summary sections for document {} (source {} chars)",
            rows.size(), documentId, sourceText.length());
    }

    private DocumentSummary row(Document doc, DocumentSummary.Kind kind,
                                DocumentSummary.Source source, ObjectNode content) {
        DocumentSummary s = new DocumentSummary();
        s.setDocument(doc);
        s.setKind(kind);
        s.setSource(source);
        s.setContent(content);
        s.setModel(summaryModel());
        return s;
    }

    private ObjectNode obj(String key, String value) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put(key, value == null ? "" : value);
        return n;
    }

    /**
     * Key findings keep the model's FACT / INTERPRETATION label per item so
     * the UI can visually separate grounded facts from interpretation.
     */
    private ObjectNode findings(JsonNode sections) {
        ObjectNode out = objectMapper.createObjectNode();
        var arr = out.putArray("findings");
        JsonNode src = sections.get("key_findings");
        if (src != null && src.isArray()) {
            for (JsonNode item : src) {
                var o = arr.addObject();
                if (item.isTextual()) {
                    o.put("text", item.asText());
                    o.put("type", "FACT");
                } else {
                    o.put("text", text(item, "text"));
                    String type = text(item, "type").toUpperCase();
                    o.put("type", type.equals("INTERPRETATION") ? "INTERPRETATION" : "FACT");
                }
            }
        }
        return out;
    }

    /** Array of objects with the given fields, reading the named source array. */
    private ObjectNode arr(String outKey, JsonNode sections, String srcKey,
                           String primaryField, String secondaryField) {
        ObjectNode out = objectMapper.createObjectNode();
        var arr = out.putArray(outKey);
        JsonNode src = sections.get(srcKey);
        if (src != null && src.isArray()) {
            for (JsonNode item : src) {
                var o = arr.addObject();
                if (item.isTextual()) {
                    o.put(primaryField, item.asText());
                } else {
                    o.put(primaryField, text(item, primaryField));
                    if (secondaryField != null) {
                        o.put(secondaryField, text(item, secondaryField));
                    }
                }
            }
        }
        return out;
    }

    private ObjectNode strArray(String outKey, JsonNode sections, String srcKey) {
        ObjectNode out = objectMapper.createObjectNode();
        var arr = out.putArray(outKey);
        JsonNode src = sections.get(srcKey);
        if (src != null && src.isArray()) {
            for (JsonNode item : src) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    arr.add(item.asText());
                } else if (item.isObject() && item.has("text")) {
                    arr.add(item.get("text").asText());
                }
            }
        }
        return out;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private void markPending(UUID documentId) {
        updateStatus(documentId, Document.SummaryStatus.PENDING, null);
    }

    private void markReady(UUID documentId) {
        updateStatus(documentId, Document.SummaryStatus.READY, null);
    }

    private void markFailed(UUID documentId, String message) {
        updateStatus(documentId, Document.SummaryStatus.FAILED, truncate(message, 300));
    }

    private void updateStatus(UUID documentId, Document.SummaryStatus status, String error) {
        try {
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setSummaryStatus(status);
                doc.setSummaryError(error);
                documentRepository.save(doc);
            });
        } catch (Exception e) {
            log.warn("Could not update summary status for document {}", documentId, e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------
    // Prompts
    // ------------------------------------------------------------------

    private static final String SEGMENT_SYSTEM = """
        You are a document condensation engine.
        Rewrite the given segment as a dense digest that keeps every concrete fact,
        name, date, number, percentage and statistic that appears in it.
        Do not add information, do not speculate, do not use outside knowledge.
        Output only the digest text.
        """;

    private static final String SECTIONS_SYSTEM = """
        You are a document analysis engine. You work ONLY from the document text provided.
        Rules:
        1. Never invent facts, names, dates, numbers or quotes that are not in the document.
        2. Do not use outside knowledge.
        3. Reply with ONLY a single JSON object. No markdown fences, no commentary.
        4. If the document does not contain something, return an empty array for that field.
        """;

    private static final String SECTIONS_PROMPT = """
        Analyze the document below and return a JSON object with exactly these fields:

        {
          "executive_summary": "3-5 sentence neutral summary of the document",
          "key_findings": [
            {"text": "finding stated in the document", "type": "FACT"},
            {"text": "reasonable reading of what the document implies", "type": "INTERPRETATION"}
          ],
          "entities": [
            {"name": "entity as written in the document", "type": "PERSON|ORGANIZATION|LOCATION|PRODUCT|EVENT|OTHER"}
          ],
          "dates_and_stats": [
            {"text": "a date, figure, percentage or statistic exactly as stated, with its context"}
          ],
          "topics": ["topic clearly covered by the document"],
          "suggested_questions": ["a question this document can answer from its own text"]
        }

        Guidance:
        - "key_findings": mark each item FACT when it is directly stated in the document,
          INTERPRETATION when it is a reasonable reading but not literally stated.
        - "entities" and "dates_and_stats" are extracted verbatim facts: at most 15 each,
          only what actually appears in the text.
        - "topics" and "suggested_questions": at most 8 and 6 respectively.
        - If the document text is insufficient for a field, return an empty array.
        """;
}
