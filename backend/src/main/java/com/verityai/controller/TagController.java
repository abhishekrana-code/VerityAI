package com.verityai.controller;

import com.verityai.dto.request.CreateTagRequest;
import com.verityai.dto.request.UpdateTagRequest;
import com.verityai.dto.response.TagResponse;
import com.verityai.entity.Tag;
import com.verityai.entity.User;
import com.verityai.repository.DocumentTagRepository;
import com.verityai.security.CurrentUser;
import com.verityai.service.document.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;
    private final DocumentTagRepository documentTagRepository;

    @GetMapping
    public ResponseEntity<List<TagResponse>> list(@CurrentUser User user) {
        List<Tag> tags = tagService.list(user.getId());
        // One grouped query for usage counts across all tags (no N+1)
        var counts = documentTagRepository.countByUser(user.getId()).stream()
            .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
        return ResponseEntity.ok(tags.stream().map(t -> {
            TagResponse resp = new TagResponse();
            resp.setId(t.getId());
            resp.setName(t.getName());
            resp.setColor(t.getColor());
            resp.setCreatedAt(t.getCreatedAt());
            resp.setDocumentCount(counts.getOrDefault(t.getId(), 0L));
            return resp;
        }).collect(Collectors.toList()));
    }

    @PostMapping
    public ResponseEntity<TagResponse> create(@CurrentUser User user,
                                              @Valid @RequestBody CreateTagRequest request) {
        Tag tag = tagService.create(user.getId(), request.getName(), request.getColor());
        TagResponse resp = new TagResponse();
        resp.setId(tag.getId());
        resp.setName(tag.getName());
        resp.setColor(tag.getColor());
        resp.setCreatedAt(tag.getCreatedAt());
        resp.setDocumentCount(0L);
        return ResponseEntity.ok(resp);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TagResponse> update(@CurrentUser User user,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpdateTagRequest request) {
        tagService.rename(user.getId(), id, request.getName(), request.getColor());
        return list(user).getBody().stream()
            .filter(t -> t.getId().equals(id))
            .findFirst()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        tagService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
