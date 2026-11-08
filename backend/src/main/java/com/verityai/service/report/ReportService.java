package com.verityai.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.verityai.dto.response.ReportListResponse;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentSummary;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.DocumentSummaryRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Report index over processed documents and their persisted summary sections.
 * One page query plus one batched summary query; no per-document lookups.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int EXECUTIVE_EXCERPT_CHARS = 900;
    private static final int MAX_FINDINGS = 6;
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentRepository documentRepository;
    private final DocumentSummaryRepository summaryRepository;

    @Transactional(readOnly = true)
    public ReportListResponse list(UUID userId, int page, int size, String q, String summaryStatus) {
        Document.SummaryStatus status = parseStatus(summaryStatus);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Document> documents = documentRepository.findAll(specification(userId, q, status), pageable);

        List<UUID> ids = documents.getContent().stream().map(Document::getId).toList();
        Map<UUID, List<DocumentSummary>> summaries = ids.isEmpty()
            ? Map.of()
            : summaryRepository.findByDocumentIdIn(ids).stream()
                .collect(Collectors.groupingBy(section -> section.getDocument().getId(),
                    LinkedHashMap::new, Collectors.toList()));

        ReportListResponse response = new ReportListResponse();
        response.setContent(documents.getContent().stream()
            .map(document -> toItem(document, summaries.getOrDefault(document.getId(), List.of())))
            .toList());
        response.setPage(documents.getNumber());
        response.setSize(documents.getSize());
        response.setTotalElements(documents.getTotalElements());
        response.setTotalPages(documents.getTotalPages());
        response.setFirst(documents.isFirst());
        response.setLast(documents.isLast());
        return response;
    }

    private ReportListResponse.Item toItem(Document document, List<DocumentSummary> sections) {
        ReportListResponse.Item item = new ReportListResponse.Item();
        item.setDocumentId(document.getId());
        item.setDocumentName(document.getOriginalFilename());
        item.setPageCount(document.getPageCount());
        item.setUpdatedAt(document.getUpdatedAt());
        item.setSummaryStatus(document.getSummaryStatus() == null
            ? "NONE" : document.getSummaryStatus().name());
        item.setSummaryError(document.getSummaryError());
        item.setSectionCount(sections.size());
        item.setModel(sections.stream()
            .map(DocumentSummary::getModel)
            .filter(model -> model != null && !model.isBlank())
            .findFirst().orElse(null));
        item.setGeneratedAt(sections.stream()
            .map(DocumentSummary::getGeneratedAt)
            .filter(generatedAt -> generatedAt != null)
            .max(Comparator.naturalOrder())
            .orElse(null));

        DocumentSummary executive = find(sections, DocumentSummary.Kind.EXECUTIVE_SUMMARY);
        if (executive != null) {
            String summary = executive.getContent().path("summary").asText("");
            item.setExecutiveSummary(summary.length() <= EXECUTIVE_EXCERPT_CHARS
                ? summary : summary.substring(0, EXECUTIVE_EXCERPT_CHARS) + "…");
        } else {
            item.setExecutiveSummary("");
        }

        DocumentSummary findings = find(sections, DocumentSummary.Kind.KEY_FINDINGS);
        item.setKeyFindings(findings == null ? List.of() : toFindings(findings.getContent()));
        item.setEntityCount(count(find(sections, DocumentSummary.Kind.ENTITIES), "entities"));
        item.setDateStatCount(count(find(sections, DocumentSummary.Kind.DATES_AND_STATS), "items"));
        item.setTopicCount(count(find(sections, DocumentSummary.Kind.TOPICS), "topics"));
        item.setQuestionCount(count(find(sections, DocumentSummary.Kind.SUGGESTED_QUESTIONS), "questions"));
        return item;
    }

    private static DocumentSummary find(List<DocumentSummary> sections, DocumentSummary.Kind kind) {
        return sections.stream().filter(section -> section.getKind() == kind).findFirst().orElse(null);
    }

    private static List<ReportListResponse.Finding> toFindings(JsonNode content) {
        List<ReportListResponse.Finding> findings = new ArrayList<>();
        JsonNode array = content.get("findings");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                if (findings.size() >= MAX_FINDINGS) {
                    break;
                }
                ReportListResponse.Finding finding = new ReportListResponse.Finding();
                finding.setText(node.path("text").asText(""));
                String type = node.path("type").asText("FACT").toUpperCase(Locale.ROOT);
                finding.setType("INTERPRETATION".equals(type) ? "INTERPRETATION" : "FACT");
                if (!finding.getText().isBlank()) {
                    findings.add(finding);
                }
            }
        }
        return findings;
    }

    private static int count(DocumentSummary section, String arrayField) {
        if (section == null) {
            return 0;
        }
        JsonNode array = section.getContent().get(arrayField);
        return array != null && array.isArray() ? array.size() : 0;
    }

    private Specification<Document> specification(UUID userId, String q, Document.SummaryStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            predicates.add(cb.equal(root.get("status"), Document.DocumentStatus.READY));
            if (status != null) {
                predicates.add(cb.equal(root.get("summaryStatus"), status));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("originalFilename")), like));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Document.SummaryStatus parseStatus(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Document.SummaryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Invalid summary status: " + value);
        }
    }
}
