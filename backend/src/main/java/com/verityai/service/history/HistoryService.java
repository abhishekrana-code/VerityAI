package com.verityai.service.history;

import com.verityai.dto.response.HistoryListResponse;
import com.verityai.entity.DocumentProcessingEvent;
import com.verityai.repository.DocumentProcessingEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read model for the standalone History page: the user's persisted document
 * processing events, newest first. One page query with the document joined.
 */
@Service
@RequiredArgsConstructor
public class HistoryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentProcessingEventRepository eventRepository;

    @Transactional(readOnly = true)
    public HistoryListResponse processing(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentProcessingEvent> events = eventRepository.findForUser(userId, pageable);

        HistoryListResponse response = new HistoryListResponse();
        response.setContent(events.getContent().stream().map(event -> {
            HistoryListResponse.Item item = new HistoryListResponse.Item();
            item.setId(event.getId());
            item.setDocumentId(event.getDocument().getId());
            item.setDocumentName(event.getDocument().getOriginalFilename());
            item.setAttempt(event.getAttempt());
            item.setStage(event.getStage().name());
            item.setErrorCode(event.getErrorCode());
            item.setErrorMessage(event.getErrorMessage());
            item.setCreatedAt(event.getCreatedAt());
            return item;
        }).toList());
        response.setPage(events.getNumber());
        response.setSize(events.getSize());
        response.setTotalElements(events.getTotalElements());
        response.setTotalPages(events.getTotalPages());
        response.setFirst(events.isFirst());
        response.setLast(events.isLast());
        return response;
    }
}
