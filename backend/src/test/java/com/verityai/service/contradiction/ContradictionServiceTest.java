package com.verityai.service.contradiction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.Contradiction.ContradictionType;
import com.verityai.entity.Contradiction.Severity;
import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.repository.ContradictionRepository;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.ai.LlmService;
import com.verityai.service.contradiction.ContradictionService.CandidatePair;
import com.verityai.service.contradiction.ContradictionService.ClaimCandidate;
import com.verityai.service.contradiction.ContradictionService.ClassifiedPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the deterministic layer of contradiction detection:
 * claim/entity/number extraction, bounded candidate generation, class mapping,
 * JSON handling, and the prompt contract that rejects non-contradictions.
 */
class ContradictionServiceTest {

    private ContradictionService service;

    @BeforeEach
    void setUp() {
        service = new ContradictionService(
            mock(DocumentRepository.class),
            mock(DocumentChunkRepository.class),
            mock(ContradictionRepository.class),
            mock(UserRepository.class),
            mock(LlmService.class),
            new ObjectMapper(),
            mock(org.springframework.transaction.support.TransactionTemplate.class));
    }

    // -----------------------------------------------------------------------
    // Deterministic extraction
    // -----------------------------------------------------------------------

    @Test
    void properKeysFindCapitalizedSubjectsButNotSentenceStartStopwords() {
        Set<String> keys = ContradictionService.properKeys(
            "The Alpha Corp reported revenue growth in Berlin.");

        assertThat(keys).containsExactly("alpha", "corp", "berlin");
        assertThat(keys).doesNotContain("the", "reported");
    }

    @Test
    void topicKeysFindLowercaseSubjectsOnlyWhenLongEnough() {
        Set<String> keys = ContradictionService.topicKeys(
            "Revenue grew and costs fell for the company.");

        assertThat(keys).contains("revenue", "costs", "company");
        assertThat(keys).doesNotContain("grew", "fell", "the");
    }

    @Test
    void numberAndYearKeysAreNormalized() {
        assertThat(ContradictionService.numberKeys("Revenue grew 42% and costs 1,200 in 2023."))
            .containsExactly("42%", "1200", "2023");
        assertThat(ContradictionService.yearKeys("In 2023 and again in 2024."))
            .containsExactly("2023", "2024");
    }

    // -----------------------------------------------------------------------
    // Candidate generation
    // -----------------------------------------------------------------------

    @Test
    void candidatesRequireASharedSubjectAndSkipSameChunkAndIdenticalClaims() {
        Document first = document("annual-report.pdf");
        Document second = document("board-notes.pdf");
        DocumentChunk firstChunk = chunk(first, 1);
        DocumentChunk secondChunk = chunk(second, 2);

        List<ClaimCandidate> claims = List.of(
            new ClaimCandidate(first, firstChunk, "Alpha Corp reported revenue growth of 42% in 2023."),
            new ClaimCandidate(second, secondChunk, "Alpha Corp reported revenue growth of 38% in 2023."),
            new ClaimCandidate(second, secondChunk, "Unrelated weather in Paris was mild."),
            new ClaimCandidate(first, firstChunk, "Alpha Corp reported revenue growth of 42% in 2023."));

        List<CandidatePair> pairs = ContradictionService.buildCandidates(claims, 10);

        // Claim 4 duplicates claim 1 in the same chunk, so that pair is skipped;
        // claim 2 still pairs with both distinct claims from the other chunk.
        assertThat(pairs).hasSize(2);
        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.a.chunk.getId()).isNotEqualTo(pair.b.chunk.getId());
            assertThat(ContradictionService.normalize(pair.a.text))
                .isNotEqualTo(ContradictionService.normalize(pair.b.text));
        });
        assertThat(pairs).anySatisfy(pair -> {
            assertThat(pair.a.text).contains("42%");
            assertThat(pair.b.text).contains("38%");
        });
    }

    @Test
    void lowercaseClaimsCanStillPairThroughSharedNumbersAndTopics() {
        Document first = document("a.pdf");
        Document second = document("b.pdf");

        List<ClaimCandidate> claims = List.of(
            new ClaimCandidate(first, chunk(first, 1), "revenue grew 42 percent in 2023."),
            new ClaimCandidate(second, chunk(second, 2), "revenue grew 38 percent in 2023."));

        List<CandidatePair> pairs = ContradictionService.buildCandidates(claims, 10);

        assertThat(pairs).hasSize(1);
    }

    @Test
    void candidateGenerationIsCapped() {
        Document first = document("a.pdf");
        Document second = document("b.pdf");
        List<ClaimCandidate> claims = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            claims.add(new ClaimCandidate(first, chunk(first, 1),
                "Alpha Corp reported revenue of " + (40 + i) + "% in 2023."));
            claims.add(new ClaimCandidate(second, chunk(second, 2),
                "Alpha Corp reported revenue of " + (50 + i) + "% in 2023."));
        }

        assertThat(ContradictionService.buildCandidates(claims, 5)).hasSize(5);
    }

    @Test
    void candidateGenerationReturnsEmptyForFewerThanTwoClaims() {
        Document first = document("a.pdf");
        assertThat(ContradictionService.buildCandidates(
            List.of(new ClaimCandidate(first, chunk(first, 1), "Alpha Corp grew.")), 10)).isEmpty();
        assertThat(ContradictionService.buildCandidates(List.of(), 10)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Class / severity mapping
    // -----------------------------------------------------------------------

    @Test
    void mapTypeAcceptsExactlyTheFiveApprovedClassesAndAliases() {
        assertThat(ContradictionService.mapType("NUMERIC_CONFLICT")).isEqualTo(ContradictionType.NUMERIC_CONFLICT);
        assertThat(ContradictionService.mapType("temporal")).isEqualTo(ContradictionType.TEMPORAL_CONFLICT);
        assertThat(ContradictionService.mapType("attribute conflict")).isEqualTo(ContradictionType.ATTRIBUTE_CONFLICT);
        assertThat(ContradictionService.mapType("CAUSAL")).isEqualTo(ContradictionType.CAUSAL_CONFLICT);
        assertThat(ContradictionService.mapType("polarity-conflict")).isEqualTo(ContradictionType.POLARITY_CONFLICT);
        assertThat(ContradictionService.mapType("SCOPE_CONFLICT")).isNull();
        assertThat(ContradictionService.mapType("")).isNull();
    }

    @Test
    void mapSeverityOnlyAcceptsImpactLevels() {
        assertThat(ContradictionService.mapSeverity("LOW")).isEqualTo(Severity.LOW);
        assertThat(ContradictionService.mapSeverity("medium")).isEqualTo(Severity.MEDIUM);
        assertThat(ContradictionService.mapSeverity("HIGH")).isEqualTo(Severity.HIGH);
        assertThat(ContradictionService.mapSeverity("0.92")).isNull();
        assertThat(ContradictionService.mapSeverity("")).isNull();
    }

    // -----------------------------------------------------------------------
    // Model-response handling
    // -----------------------------------------------------------------------

    @Test
    void parseAcceptsOnlyFlaggedPairsWithValidClassAndSeverity() {
        CandidatePair pair = pair();

        List<ClassifiedPair> accepted = service.parseClassifications(List.of(pair), """
            [{"i":0,"contradiction":true,"type":"NUMERIC_CONFLICT","severity":"HIGH","why":"values differ"}]
            """);

        assertThat(accepted).hasSize(1);
        assertThat(accepted.get(0).type).isEqualTo(ContradictionType.NUMERIC_CONFLICT);
        assertThat(accepted.get(0).severity).isEqualTo(Severity.HIGH);
        assertThat(accepted.get(0).explanation).isEqualTo("values differ");
    }

    @Test
    void parseRejectsFalseFlagUnknownClassUnknownSeverityAndDuplicateIndexes() {
        CandidatePair pair = pair();

        List<ClassifiedPair> accepted = service.parseClassifications(List.of(pair), """
            [{"i":0,"contradiction":false,"type":"NUMERIC_CONFLICT","severity":"HIGH"},
             {"i":0,"contradiction":true,"type":"SCOPE_CONFLICT","severity":"HIGH"},
             {"i":0,"contradiction":true,"type":"NUMERIC_CONFLICT","severity":"0.9"},
             {"i":0,"contradiction":true,"type":"NUMERIC_CONFLICT","severity":"LOW"},
             {"i":0,"contradiction":true,"type":"POLARITY_CONFLICT","severity":"HIGH"}]
            """);

        // The first valid accepted row wins; later rows for the same index are ignored.
        assertThat(accepted).hasSize(1);
        assertThat(accepted.get(0).type).isEqualTo(ContradictionType.NUMERIC_CONFLICT);
        assertThat(accepted.get(0).severity).isEqualTo(Severity.LOW);
    }

    @Test
    void parseReturnsNullForInvalidJsonSoEarlierFindingsAreKept() {
        assertThat(service.parseClassifications(List.of(pair()), "the model rambled")).isNull();
        assertThat(service.parseClassifications(List.of(pair()), "{\"not\":\"an array\"}")).isNull();
    }

    @Test
    void parseIgnoresOutOfRangePairIndexes() {
        List<ClassifiedPair> accepted = service.parseClassifications(List.of(pair()), """
            [{"i":99,"contradiction":true,"type":"NUMERIC_CONFLICT","severity":"HIGH"}]
            """);

        assertThat(accepted).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Pair identity / prompt contract
    // -----------------------------------------------------------------------

    @Test
    void pairKeyIsCanonicalAndStable() {
        Document first = document("a.pdf");
        Document second = document("b.pdf");
        ClaimCandidate a = new ClaimCandidate(first, chunk(first, 1), "Alpha Corp grew 42% in 2023.");
        ClaimCandidate b = new ClaimCandidate(second, chunk(second, 2), "Alpha Corp grew 38% in 2023.");

        assertThat(ContradictionService.pairKey(a, b))
            .isEqualTo(ContradictionService.pairKey(b, a))
            .hasSize(64);
    }

    @Test
    void promptIncludesBothClaimsAndCitationsAndRejectsNonContradictions() {
        CandidatePair pair = pair();
        String prompt = service.buildUserPrompt(List.of(pair));

        assertThat(prompt).contains("a.pdf", "page 1", "b.pdf", "page 2");
        assertThat(prompt).contains("Alpha Corp reported revenue growth of 42% in 2023.");
        assertThat(prompt).contains("Alpha Corp reported revenue growth of 38% in 2023.");
        assertThat(prompt).contains("scope differences");
        assertThat(prompt).contains("complementary facts");
        assertThat(prompt).contains("different time windows");
        assertThat(prompt).contains("paraphrases");
        assertThat(prompt).contains("potential impact");
        assertThat(prompt).contains("never a confidence or probability");
    }

    @Test
    void systemPromptStatesTheFiveClassesAndExclusions() {
        String prompt = ContradictionService.SYSTEM_PROMPT.replaceAll("\\s+", " ");
        assertThat(prompt)
            .contains("NUMERIC_CONFLICT", "TEMPORAL_CONFLICT", "ATTRIBUTE_CONFLICT",
                "CAUSAL_CONFLICT", "POLARITY_CONFLICT")
            .contains("scope differences", "complementary facts",
                "different time windows", "paraphrases")
            .contains("Severity measures impact, not confidence");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CandidatePair pair() {
        Document first = document("a.pdf");
        Document second = document("b.pdf");
        ClaimCandidate a = new ClaimCandidate(first, chunk(first, 1),
            "Alpha Corp reported revenue growth of 42% in 2023.");
        ClaimCandidate b = new ClaimCandidate(second, chunk(second, 2),
            "Alpha Corp reported revenue growth of 38% in 2023.");
        return new CandidatePair(a, b, "alpha", 10);
    }

    private static Document document(String name) {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setOriginalFilename(name);
        return document;
    }

    private static DocumentChunk chunk(Document document, int page) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setDocument(document);
        chunk.setPageNumber(page);
        chunk.setChunkIndex(0);
        chunk.setContent("content");
        return chunk;
    }
}
