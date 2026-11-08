package com.verityai.controller;

import com.verityai.dto.response.HistoryListResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.history.HistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Processing-history feed (Phase 3 page gap). Research sessions are served by
 * the existing {@code GET /conversations}; this endpoint covers document
 * processing events across the whole library.
 */
@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/processing")
    public ResponseEntity<HistoryListResponse> processing(
            @CurrentUser User user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(historyService.processing(user.getId(), page, size));
    }
}
