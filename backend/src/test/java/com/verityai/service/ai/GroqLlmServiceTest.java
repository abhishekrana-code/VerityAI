package com.verityai.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
