package com.verityai.controller;

import com.verityai.dto.request.SearchRequest;
import com.verityai.dto.response.SearchResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.search.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Semantic/hybrid search (Phase 2, Feature 4).
 *
 * <p>POST (not GET) so a search can carry a document scope and a result cap
 * without URL-length limits; CSRF applies exactly like other mutations, and
 * the session/auth pipeline is unchanged.</p>
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @PostMapping
    public ResponseEntity<SearchResponse> search(
            @CurrentUser User user,
            @Valid @RequestBody SearchRequest request) {
        return ResponseEntity.ok(searchService.search(user, request));
    }
}
