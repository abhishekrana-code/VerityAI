package com.verityai.controller;

import com.verityai.dto.response.VerificationListResponse;
import com.verityai.dto.response.VerificationStatsResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.verification.VerificationListService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standalone verification index (Phase 3 page gap). Reads stored claim
 * verification results; running verification itself still uses
 * {@code POST /conversations/{id}/messages/{messageId}/verify} so the existing
 * rate-limited, ownership-checked flow is preserved.
 */
@RestController
@RequestMapping("/api/v1/verifications")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationListService verificationListService;

    @GetMapping
    public ResponseEntity<VerificationListResponse> list(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(verificationListService.list(user.getId(), page, size, status));
    }

    @GetMapping("/stats")
    public ResponseEntity<VerificationStatsResponse> stats(@CurrentUser User user) {
        return ResponseEntity.ok(verificationListService.stats(user.getId()));
    }
}
