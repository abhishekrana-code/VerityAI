package com.verityai.controller;

import com.verityai.dto.request.RebuildKnowledgeGraphRequest;
import com.verityai.dto.response.KnowledgeGraphRebuildResponse;
import com.verityai.dto.response.KnowledgeGraphResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.knowledge.KnowledgeGraphService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Knowledge graph (Phase 3, Feature 7). PostgreSQL only; no external graph
 * database. Reads are ownership-scoped and bounded; rebuilds are explicit or
 * happen during document processing / bounded startup backfill.
 */
@RestController
@RequestMapping("/api/v1/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;

    @GetMapping
    public ResponseEntity<KnowledgeGraphResponse> graph(
            @CurrentUser User user,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "120") @Min(1) @Max(300) int limit) {
        return ResponseEntity.ok(knowledgeGraphService.graph(user, documentId, type, q, limit));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<KnowledgeGraphRebuildResponse> rebuild(
            @CurrentUser User user,
            @RequestBody(required = false) RebuildKnowledgeGraphRequest request) {
        return ResponseEntity.ok(knowledgeGraphService.rebuildForUser(
            user.getId(), request == null ? null : request.getDocumentIds()));
    }
}
