package com.verityai.service.verification;

import com.verityai.dto.response.VerificationListResponse;
import com.verityai.dto.response.VerificationStatsResponse;
import com.verityai.entity.Message;
import com.verityai.entity.MessageClaimVerification;
import com.verityai.entity.MessageClaimVerification.SupportClass;
import com.verityai.exception.ApiException;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageClaimVerificationRepository;
import com.verityai.repository.MessageRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read model for the standalone Verification page.
 *
 * <p>Lists assistant answers with their stored claim verification, computing
 * status/coverage deterministically from the persisted rows (no model call).
 * All lookups are batched per page, so the page never triggers N+1 queries.</p>
 */
@Service
@RequiredArgsConstructor
public class VerificationListService {

    private static final int EXCERPT_CHARS = 260;
    private static final int MAX_PAGE_SIZE = 100;

    private final MessageRepository messageRepository;
    private final MessageClaimVerificationRepository verificationRepository;
    private final MessageCitationRepository citationRepository;

    @Transactional(readOnly = true)
    public VerificationListResponse list(UUID userId, int page, int size, String status) {
        // Defensive clamp: bean validation on @RequestParam is not active here,
        // so keep one request from asking for an unbounded page.
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Message> messages = messageRepository.findAll(specification(userId, status), pageable);

        List<UUID> messageIds = messages.getContent().stream().map(Message::getId).toList();

        Map<UUID, List<MessageClaimVerification>> verifications = messageIds.isEmpty()
            ? Map.of()
            : verificationRepository.findByMessageIdIn(messageIds).stream()
                .collect(Collectors.groupingBy(row -> row.getMessage().getId(),
                    LinkedHashMap::new, Collectors.toList()));

        Map<UUID, Integer> citationCounts = new LinkedHashMap<>();
        if (!messageIds.isEmpty()) {
            for (Object[] row : citationRepository.countByMessageIds(messageIds)) {
                citationCounts.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }

        VerificationListResponse response = new VerificationListResponse();
        response.setContent(messages.getContent().stream()
            .map(message -> toItem(message,
                message.getConversation().getTitle(),
                verifications.getOrDefault(message.getId(), List.of()),
                citationCounts.getOrDefault(message.getId(), 0)))
            .toList());
        response.setPage(messages.getNumber());
        response.setSize(messages.getSize());
        response.setTotalElements(messages.getTotalElements());
        response.setTotalPages(messages.getTotalPages());
        response.setFirst(messages.isFirst());
        response.setLast(messages.isLast());
        return response;
    }

    @Transactional(readOnly = true)
    public VerificationStatsResponse stats(UUID userId) {
        VerificationStatsResponse response = new VerificationStatsResponse();
        response.setTotal(messageRepository.count(specification(userId, "ALL")));
        response.setVerified(messageRepository.count(specification(userId, "VERIFIED")));
        response.setPartial(messageRepository.count(specification(userId, "PARTIAL")));
        response.setCompleted(messageRepository.count(specification(userId, "COMPLETED")));
        response.setUnverified(messageRepository.count(specification(userId, "UNVERIFIED")));
        return response;
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private VerificationListResponse.Item toItem(Message message, String conversationTitle,
                                                 List<MessageClaimVerification> rows,
                                                 int citationCount) {
        VerificationListResponse.Item item = new VerificationListResponse.Item();
        item.setMessageId(message.getId());
        item.setConversationId(message.getConversation().getId());
        item.setConversationTitle(conversationTitle);
        item.setContentExcerpt(excerpt(message.getContent()));
        item.setCreatedAt(message.getCreatedAt());
        item.setMessageModel(message.getModel());
        item.setEvidenceStatus(message.getEvidenceStatus() == null ? null : message.getEvidenceStatus().name());
        item.setCitationCount(citationCount);

        boolean partial = rows.stream().anyMatch(row -> row.getSupportClass() == null);
        item.setStatus(rows.isEmpty() ? "NOT_REQUESTED" : (partial ? "PARTIAL" : "COMPLETED"));
        item.setCoverage(coverage(rows));
        item.setVerificationModel(rows.isEmpty() ? null : rows.get(0).getModel());
        item.setVerifiedAt(rows.isEmpty() ? null : rows.get(0).getVerifiedAt());
        item.setAnalyzedClaims(rows.size());
        item.setTotalClaims(ClaimVerificationService.segmentClaims(message.getContent()).size());
        item.setClassifiedClaims((int) rows.stream()
            .filter(row -> row.getSupportClass() != null).count());
        item.setInvalidReferenceCount(invalidReferences(message.getContent(), citationCount));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SupportClass supportClass : SupportClass.values()) {
            counts.put(supportClass.name(), 0);
        }
        for (MessageClaimVerification row : rows) {
            if (row.getSupportClass() != null) {
                counts.merge(row.getSupportClass().name(), 1, Integer::sum);
            }
        }
        item.setSupportCounts(counts);
        return item;
    }

    private static Double coverage(List<MessageClaimVerification> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        long cited = rows.stream()
            .filter(row -> row.getCitationIndexes() != null && !row.getCitationIndexes().isEmpty())
            .count();
        return (double) cited / rows.size();
    }

    private static int invalidReferences(String content, int citationCount) {
        int invalid = 0;
        for (String claim : ClaimVerificationService.segmentClaims(content)) {
            for (int reference : ClaimVerificationService.extractRefs(claim)) {
                if (reference < 1 || reference > citationCount) {
                    invalid++;
                }
            }
        }
        return invalid;
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS) + "…";
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    private Specification<Message> specification(UUID userId, String status) {
        String normalized = normalizeStatus(status);
        return (root, query, cb) -> {
            // One page query must not trigger a per-row conversation lookup.
            if (query != null && !Long.class.equals(query.getResultType())) {
                root.fetch("conversation", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("conversation").get("user").get("id"), userId));
            predicates.add(cb.equal(root.get("role"), Message.MessageRole.ASSISTANT));

            switch (normalized) {
                case "VERIFIED" -> predicates.add(hasRows(root, query, cb, false));
                case "UNVERIFIED" -> predicates.add(cb.not(hasRows(root, query, cb, false)));
                case "PARTIAL" -> predicates.add(hasRows(root, query, cb, true));
                case "COMPLETED" -> {
                    predicates.add(hasRows(root, query, cb, false));
                    predicates.add(cb.not(hasRows(root, query, cb, true)));
                }
                default -> {
                    // ALL
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate hasRows(Root<Message> root, CriteriaQuery<?> query,
                                     CriteriaBuilder cb, boolean onlyUnclassified) {
        Subquery<MessageClaimVerification> subquery = query.subquery(MessageClaimVerification.class);
        Root<MessageClaimVerification> row = subquery.from(MessageClaimVerification.class);
        subquery.select(row);
        Predicate match = cb.equal(row.get("message").get("id"), root.get("id"));
        if (onlyUnclassified) {
            match = cb.and(match, cb.isNull(row.get("supportClass")));
        }
        subquery.where(match);
        return cb.exists(subquery);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ALL";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ALL", "VERIFIED", "UNVERIFIED", "PARTIAL", "COMPLETED" -> normalized;
            default -> throw ApiException.validation("Invalid verification status: " + status);
        };
    }
}
