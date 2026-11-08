package com.verityai.controller;

import com.verityai.dto.request.DetectContradictionsRequest;
import com.verityai.dto.response.ContradictionDetectionResponse;
import com.verityai.dto.response.ContradictionListResponse;
import com.verityai.dto.response.ContradictionStatsResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.security.RateLimitService;
import com.verityai.service.contradiction.ContradictionListService;
import com.verityai.service.contradiction.ContradictionService;
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
 * Contradiction detection (Phase 3, Feature 8).
 *
 * <p>Reads are ownership-scoped and never call a model. Detection is explicit
 * and rate-limited because it spends one bounded model call; failures store
 * nothing and leave earlier findings untouched.</p>
 */
@RestController
@RequestMapping("/api/v1/contradictions")
@RequiredArgsConstructor
public class ContradictionController {

    private final ContradictionService contradictionService;
    private final ContradictionListService contradictionListService;
    private final RateLimitService rateLimitService;

    @GetMapping
    public ResponseEntity<ContradictionListResponse> list(
            @CurrentUser User user,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(contradictionListService.list(user.getId(), documentId, type, page, size));
    }

    @GetMapping("/stats")
    public ResponseEntity<ContradictionStatsResponse> stats(@CurrentUser User user) {
        return ResponseEntity.ok(contradictionListService.stats(user.getId()));
    }

    @PostMapping("/detect")
    public ResponseEntity<ContradictionDetectionResponse> detect(
            @CurrentUser User user,
            @RequestBody(required = false) DetectContradictionsRequest request) {
        rateLimitService.checkAiRequest(user.getId());
        return ResponseEntity.ok(contradictionService.detect(
            user.getId(), request == null ? null : request.getDocumentIds()));
    }
}
