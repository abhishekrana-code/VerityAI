package com.verityai.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for citation extraction from LLM output. The model sometimes
 * emits CJK/fullwidth brackets instead of ASCII ones; extraction must
 * normalize them so citations are never silently dropped.
 */
class GroqLlmServiceTest {

    private GroqLlmService service;

    @BeforeEach
    void setUp() {
        service = new GroqLlmService(new ObjectMapper());
    }

    @Test
    void extractsAsciiBracketCitation() {
        assertThat(service.extractCitations("Revenue grew 14 percent. [S1]"))
            .containsExactly(0);
    }

    @Test
    void extractsMultipleCitationsInOrderOfAppearance() {
        assertThat(service.extractCitations("First [S3], then [S1], finally [S2]"))
            .containsExactly(2, 0, 1);
    }

    @Test
    void deduplicatesRepeatedCitations() {
        assertThat(service.extractCitations("[S1] supports this. [S1] again, then [S2]. [S1]"))
            .containsExactly(0, 1);
    }

    @Test
    void ignoresZeroIndexMarkers() {
        assertThat(service.extractCitations("No real source here [S0]"))
            .isEmpty();
    }

    @Test
    void normalizesCjkCornerBrackets() {
        assertThat(service.extractCitations("Patent claim 【S2】 follows."))
            .containsExactly(1);
    }

    @Test
    void normalizesFullwidthBrackets() {
        assertThat(service.extractCitations("Fullwidth marker ［S3］ here."))
            .containsExactly(2);
    }

    @Test
    void normalizesJapaneseCornerQuotes() {
        assertThat(service.extractCitations("Quote style 「S1」 here."))
            .containsExactly(0);
    }

    @Test
    void normalizesMixedBracketStyles() {
        assertThat(service.extractCitations("Mixed 【S2】 and [S1] styles"))
            .containsExactly(1, 0);
    }

    @Test
    void returnsEmptyListWhenNoCitationsPresent() {
        assertThat(service.extractCitations("Insufficient evidence to answer."))
            .isEmpty();
    }

    @Test
    void ignoresMalformedMarkers() {
        assertThat(service.extractCitations("Broken [S] and [SX] and [S] markers"))
            .isEmpty();
    }

    // -----------------------------------------------------------------------
    // buildPrompt: multi-document comparison guidance (Feature 5)
    // -----------------------------------------------------------------------

    private static Document doc(String name) {
        Document d = new Document();
        d.setId(UUID.randomUUID());
        d.setOriginalFilename(name);
        d.setStoredFilename(name);
        d.setMimeType("application/pdf");
        d.setSizeBytes(1L);
        d.setSha256("b".repeat(64));
        d.setStatus(Document.DocumentStatus.READY);
        return d;
    }

    private static RetrievalService.RetrievedChunk chunk(Document document, String content, int page) {
        DocumentChunk dc = new DocumentChunk();
        dc.setId(UUID.randomUUID());
        dc.setDocument(document);
        dc.setPageNumber(page);
        dc.setChunkIndex(0);
        dc.setContent(content);
        return new RetrievalService.RetrievedChunk(dc, 0.5);
    }

    @Test
    void promptAddsComparisonGuidanceWhenEvidenceSpansTwoDocuments() {
        Document a = doc("annual-report.pdf");
        Document b = doc("audit-notes.pdf");
        String prompt = service.buildPrompt("What changed?", List.of(
            chunk(a, "Revenue grew 14 percent.", 3),
            chunk(b, "Revenue grew 9 percent.", 12)));

        assertThat(prompt)
            .contains("The evidence spans 2 documents")
            .contains("compare them explicitly")
            .contains("report the disagreement with both citations");
        assertThat(prompt).contains("[S1]").contains("[S2]");
        assertThat(prompt).contains("annual-report.pdf").contains("audit-notes.pdf");
    }

    @Test
    void promptOmitsComparisonGuidanceForSingleDocument() {
        Document only = doc("single.pdf");
        String prompt = service.buildPrompt("What changed?", List.of(
            chunk(only, "Revenue grew 14 percent.", 1),
            chunk(only, "Costs fell 3 percent.", 2)));

        assertThat(prompt)
            .doesNotContain("spans 2 documents")
            .doesNotContain("report the disagreement");
        assertThat(prompt).contains("Source [S1]").contains("Source [S2]");
    }

    @Test
    void promptGuidanceCountsDistinctDocumentsNotChunkCount() {
        Document a = doc("a.pdf");
        Document b = doc("b.pdf");
        // Three chunks, but only two distinct documents
        String prompt = service.buildPrompt("q", List.of(
            chunk(a, "one", 1), chunk(a, "two", 2), chunk(b, "three", 3)));

        assertThat(prompt).contains("The evidence spans 2 documents");
    }
}
