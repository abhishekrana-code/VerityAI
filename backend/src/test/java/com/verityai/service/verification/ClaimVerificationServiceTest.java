package com.verityai.service.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.entity.MessageClaimVerification.SupportClass;
import com.verityai.repository.MessageCitationRepository;
import com.verityai.repository.MessageClaimVerificationRepository;
import com.verityai.repository.MessageRepository;
import com.verityai.service.ai.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the deterministic layer of claim verification: segmentation,
 * citation-reference extraction, coverage, class mapping, and JSON parsing.
 * These never call a model — they are the part that must always be correct.
 */
class ClaimVerificationServiceTest {

    private ClaimVerificationService service;

    @BeforeEach
    void setUp() {
        service = new ClaimVerificationService(
            mock(MessageRepository.class),
            mock(MessageCitationRepository.class),
            mock(MessageClaimVerificationRepository.class),
            mock(LlmService.class),
            new ObjectMapper(),
            mock(org.springframework.transaction.support.TransactionTemplate.class));
    }

    // -----------------------------------------------------------------------
    // Segmentation
    // -----------------------------------------------------------------------

    @Test
    void segmentSplitsSentencesAndSkipsTinyFragments() {
        List<String> claims = ClaimVerificationService.segmentClaims(
            "Revenue grew 14 percent in 2024. Costs fell 3 percent.\nYes.");

        assertThat(claims).hasSize(2);
        assertThat(claims.get(0)).isEqualTo("Revenue grew 14 percent in 2024.");
        assertThat(claims.get(1)).isEqualTo("Costs fell 3 percent.");
    }

    @Test
    void segmentUnwrapsBulletsAndHeadings() {
        List<String> claims = ClaimVerificationService.segmentClaims(
            "## Findings\n- The audit found no material misstatements\n2) Management approved the plan");

        assertThat(claims).containsExactly(
            "The audit found no material misstatements",
            "Management approved the plan");
    }

    @Test
    void segmentOfBlankOrNullIsEmpty() {
        assertThat(ClaimVerificationService.segmentClaims(null)).isEmpty();
        assertThat(ClaimVerificationService.segmentClaims("  \n \n")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Reference extraction
    // -----------------------------------------------------------------------

    @Test
    void extractRefsNormalizesBracketStylesAndDeduplicates() {
        Set<Integer> refs = ClaimVerificationService.extractRefs(
            "As shown in [S1], 【S2】 and ［S1］ and 「S3」.");

        assertThat(refs).containsExactly(1, 2, 3);
    }

    @Test
    void computeReferencesSplitsValidAndInvalid() {
        ClaimVerificationService.ClaimDraft draft =
            new ClaimVerificationService.ClaimDraft(0, "Claim [S1] and [S2] and [S9].");
        ClaimVerificationService.computeReferences(draft, 3);

        assertThat(draft.validRefs).containsExactly(0, 1); // 0-based
        assertThat(draft.invalidRefs).containsExactly(9);  // 1-based, out of range
    }

    @Test
    void zeroIndexIsInvalid() {
        ClaimVerificationService.ClaimDraft draft =
            new ClaimVerificationService.ClaimDraft(0, "Odd marker [S0] here.");
        ClaimVerificationService.computeReferences(draft, 3);

        assertThat(draft.validRefs).isEmpty();
        assertThat(draft.invalidRefs).containsExactly(0);
    }

    // -----------------------------------------------------------------------
    // Coverage
    // -----------------------------------------------------------------------

    @Test
    void coverageIsNullWhenThereAreNoClaims() {
        assertThat(ClaimVerificationService.coverage(List.of())).isNull();
    }

    @Test
    void coverageIsFractionOfClaimsWithValidCitations() {
        ClaimVerificationService.ClaimDraft cited =
            new ClaimVerificationService.ClaimDraft(0, "Claim [S1].");
        ClaimVerificationService.computeReferences(cited, 2);
        ClaimVerificationService.ClaimDraft uncited =
            new ClaimVerificationService.ClaimDraft(1, "A claim with no citation at all.");
        ClaimVerificationService.computeReferences(uncited, 2);

        assertThat(ClaimVerificationService.coverage(List.of(cited, uncited))).isEqualTo(0.5);
    }

    // -----------------------------------------------------------------------
    // Model-response handling
    // -----------------------------------------------------------------------

    @Test
    void mapClassAcceptsAliasesAndRejectsUnknownValues() {
        assertThat(ClaimVerificationService.mapClass("SUPPORTED")).isEqualTo(SupportClass.SUPPORTED);
        assertThat(ClaimVerificationService.mapClass("partially_supported")).isEqualTo(SupportClass.PARTIALLY_SUPPORTED);
        assertThat(ClaimVerificationService.mapClass("Partial")).isEqualTo(SupportClass.PARTIALLY_SUPPORTED);
        assertThat(ClaimVerificationService.mapClass("not_supported")).isEqualTo(SupportClass.UNSUPPORTED);
        assertThat(ClaimVerificationService.mapClass("conflicting")).isEqualTo(SupportClass.CONFLICTING);
        assertThat(ClaimVerificationService.mapClass("PROBABLY_TRUE")).isNull();
        assertThat(ClaimVerificationService.mapClass("")).isNull();
    }

    @Test
    void extractJsonArrayHandlesFencesAndSurroundingProse() {
        String fenced = "```json\n[{\"i\":0,\"class\":\"SUPPORTED\",\"why\":\"ok\"}]\n```";
        assertThat(ClaimVerificationService.extractJsonArray(fenced))
            .isEqualTo("[{\"i\":0,\"class\":\"SUPPORTED\",\"why\":\"ok\"}]");

        String withProse = "Here are the results: [ {\"i\":1,\"class\":\"UNSUPPORTED\"} ] — done.";
        assertThat(ClaimVerificationService.extractJsonArray(withProse))
            .isEqualTo("[ {\"i\":1,\"class\":\"UNSUPPORTED\"} ]");
    }

    @Test
    void extractJsonArrayThrowsWhenAbsent() {
        assertThatThrownBy(() -> ClaimVerificationService.extractJsonArray("no json here"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyModelClassificationsAppliesValidEntriesAndIgnoresBadOnes() {
        ClaimVerificationService.ClaimDraft zero =
            new ClaimVerificationService.ClaimDraft(0, "First claim.");
        ClaimVerificationService.ClaimDraft one =
            new ClaimVerificationService.ClaimDraft(1, "Second claim.");
        ClaimVerificationService.ClaimDraft two =
            new ClaimVerificationService.ClaimDraft(2, "Third claim.");
        List<ClaimVerificationService.ClaimDraft> pending = List.of(zero, one, two);

        service.applyModelClassifications(pending, """
            [{"i":0,"class":"SUPPORTED","why":"matches source 1"},
             {"i":1,"class":"TOTALLY_MADE_UP","why":"ignored"},
             {"i":99,"class":"UNSUPPORTED","why":"out of range index ignored"}]
            """);

        assertThat(zero.supportClass).isEqualTo(SupportClass.SUPPORTED);
        assertThat(zero.explanation).isEqualTo("matches source 1");
        assertThat(one.supportClass).isNull();   // unknown class stays unclassified
        assertThat(two.supportClass).isNull();   // unknown index stays unclassified
    }

    @Test
    void applyModelClassificationsOnGarbageLeavesClaimsUntouched() {
        ClaimVerificationService.ClaimDraft draft =
            new ClaimVerificationService.ClaimDraft(0, "Claim.");
        service.applyModelClassifications(List.of(draft), "the model rambled instead of JSON");

        assertThat(draft.supportClass).isNull();
        assertThat(draft.explanation).isNull();
    }
}
