package com.verityai.controller;

import com.verityai.dto.response.ReportListResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.report.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report index over processed documents (Phase 3 page gap). Regeneration stays
 * on the existing ownership-checked, rate-limited
 * {@code POST /documents/{id}/summaries/regenerate} endpoint.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping
    public ResponseEntity<ReportListResponse> list(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String summaryStatus) {
        return ResponseEntity.ok(reportService.list(user.getId(), page, size, q, summaryStatus));
    }
}
