package com.verityai.service.contradiction;

import com.verityai.dto.response.ContradictionListResponse;
import com.verityai.dto.response.ContradictionStatsResponse;
import com.verityai.entity.Contradiction;
import com.verityai.entity.Contradiction.ContradictionType;
import com.verityai.entity.Contradiction.Severity;
import com.verityai.exception.ApiException;
import com.verityai.repository.ContradictionRepository;
import com.verityai.repository.DocumentRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for stored contradiction findings (Feature 8).
 *
 * <p>All lookups are ownership-scoped, paginated and batched: document names
 * are fetched with the page query so listing never triggers N+1 queries.
 * Reading findings never calls a model.</p>
 */
@Service
@RequiredArgsConstructor
public class ContradictionListService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ContradictionRepository contradictionRepository;
    private final DocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public ContradictionListResponse list(UUID userId, UUID documentId, String type, int page, int size) {
        if (documentId != null && documentRepository
            .findByIdAndUserIdAndDeletedAtIsNull(documentId, userId).isEmpty()) {
            throw ApiException.notFound("Document not found");
        }
        ContradictionType parsedType = parseType(type);

        // Defensive clamp: @Min/@Max on @RequestParam is not enforced here.
        Pageable pageable = PageRequest.of(Math.max(page, 0),
            Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "detectedAt")
                .and(Sort.by(Sort.Direction.DESC, "createdAt")));

        Page<Contradiction> rows = contradictionRepository.findAll(
            specification(userId, documentId, parsedType), pageable);

        ContradictionListResponse response = new ContradictionListResponse();
        response.setContent(rows.getContent().stream().map(this::toItem).toList());
        response.setPage(rows.getNumber());
        response.setSize(rows.getSize());
        response.setTotalElements(rows.getTotalElements());
        response.setTotalPages(rows.getTotalPages());
        response.setFirst(rows.isFirst());
        response.setLast(rows.isLast());
        return response;
    }

    @Transactional(readOnly = true)
    public ContradictionStatsResponse stats(UUID userId) {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (ContradictionType type : ContradictionType.values()) {
            byType.put(type.name(), 0L);
        }
        for (Object[] row : contradictionRepository.countByType(userId)) {
            byType.put(((ContradictionType) row[0]).name(), ((Number) row[1]).longValue());
        }

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.name(), 0L);
        }
        for (Object[] row : contradictionRepository.countBySeverity(userId)) {
            bySeverity.put(((Severity) row[0]).name(), ((Number) row[1]).longValue());
        }

        ContradictionStatsResponse response = new ContradictionStatsResponse();
        response.setTotal(byType.values().stream().mapToLong(Long::longValue).sum());
        response.setByType(byType);
        response.setBySeverity(bySeverity);
        response.setLastDetectedAt(contradictionRepository.findLatestDetectedAt(userId));
        return response;
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private ContradictionListResponse.Item toItem(Contradiction row) {
        ContradictionListResponse.Item item = new ContradictionListResponse.Item();
        item.setId(row.getId());
        item.setContradictionType(row.getContradictionType().name());
        item.setSeverity(row.getSeverity().name());
        item.setSubjectEntity(row.getSubjectEntity());
        item.setExplanation(row.getExplanation());
        item.setModel(row.getModel());
        item.setDetectedAt(row.getDetectedAt());

        item.setClaimA(row.getClaimA());
        item.setDocumentAId(row.getDocumentA().getId());
        item.setDocumentAName(row.getDocumentA().getOriginalFilename());
        item.setPageA(row.getPageA());
        item.setChunkAId(row.getChunkA() == null ? null : row.getChunkA().getId());

        item.setClaimB(row.getClaimB());
        item.setDocumentBId(row.getDocumentB().getId());
        item.setDocumentBName(row.getDocumentB().getOriginalFilename());
        item.setPageB(row.getPageB());
        item.setChunkBId(row.getChunkB() == null ? null : row.getChunkB().getId());
        return item;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    private Specification<Contradiction> specification(UUID userId, UUID documentId,
                                                       ContradictionType type) {
        return (root, query, cb) -> {
            // One page query must not trigger a per-row document lookup.
            if (query != null && !Long.class.equals(query.getResultType())) {
                root.fetch("documentA", JoinType.INNER);
                root.fetch("documentB", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));
            if (documentId != null) {
                predicates.add(cb.or(
                    cb.equal(root.get("documentA").get("id"), documentId),
                    cb.equal(root.get("documentB").get("id"), documentId)));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("contradictionType"), type));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static ContradictionType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ContradictionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Invalid contradiction type: " + value);
        }
    }
}
