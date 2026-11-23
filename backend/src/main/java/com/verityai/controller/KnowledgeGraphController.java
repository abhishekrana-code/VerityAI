package com.verityai.controller;

import com.verityai.dto.response.KnowledgeConnectionsResponse;
import com.verityai.dto.response.KnowledgeEntityIndexResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.knowledge.KnowledgeGraphService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Entity index and document connections (Phase 3, Feature 7). PostgreSQL only;
 * no external graph database and no stored edges. Reads are ownership-scoped
 * and bounded; extraction happens when a document is processed.
 */
@RestController
@RequestMapping("/api/v1/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;

    /**
     * Merged entity index: one row per normalized name, with every document that
     * mentions it. This is the readable replacement for repeating an entity once
     * per document in the canvas.
     *
     * <p>Noise rows (bare years, two-letter acronyms and table labels) are
     * hidden by default; {@code includeNoise=true} returns the raw index. Use
     * {@code types} for a multi-type filter such as the default subject view.</p>
     */
    @GetMapping("/entities")
    public ResponseEntity<KnowledgeEntityIndexResponse> entities(
            @CurrentUser User user,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeNoise,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return ResponseEntity.ok(
            knowledgeGraphService.entityIndex(user, documentId, type, types, q, includeNoise, limit));
    }

    /** Document pairs that mention the same entities (doc-document connections). */
    @GetMapping("/connections")
    public ResponseEntity<KnowledgeConnectionsResponse> connections(
            @CurrentUser User user,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok(knowledgeGraphService.connections(user, limit));
    }
}
