package com.verityai.eval;

import com.verityai.eval.AnswerQualityScorer.CaseResult;
import com.verityai.eval.AnswerQualityScorer.EvalCase;
import com.verityai.eval.AnswerQualityScorer.Summary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The evaluation's judgment rules — no model, no database, no network. */
class AnswerQualityScorerTest {

    private static EvalCase answerable() {
        return new EvalCase("revenue", "What was revenue growth?",
            List.of("annual.pdf"), false, List.of("42 percent", "42%"));
    }

    private static EvalCase refusal() {
        return new EvalCase("salary", "What was the CEO's salary?", List.of(), true, List.of());
    }

    @Test
    void groundedAnswerInTheRightDocumentPasses() {
        CaseResult result = AnswerQualityScorer.score(
            answerable(), List.of("annual.pdf"), "Revenue grew 42 percent.");

        assertThat(result.passed()).isTrue();
        assertThat(result.unexpectedCitations()).isZero();
    }

    @Test
    void answerWithoutCitationsFailsEvenWhenTheFactIsRight() {
        CaseResult result = AnswerQualityScorer.score(
            answerable(), List.of(), "Revenue grew 42 percent.");

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("answer cited nothing");
    }

    @Test
    void citationToTheWrongDocumentFails() {
        CaseResult result = AnswerQualityScorer.score(
            answerable(), List.of("notes.pdf"), "Revenue grew 42 percent.");

        assertThat(result.passed()).isFalse();
        assertThat(result.expectedDocumentHit()).isFalse();
    }

    @Test
    void missingExpectedFactFails() {
        CaseResult result = AnswerQualityScorer.score(
            answerable(), List.of("annual.pdf"), "Revenue grew substantially.");

        assertThat(result.passed()).isFalse();
        assertThat(result.mentionsExpectedFact()).isFalse();
    }

    @Test
    void unanswerableQuestionPassesOnlyWhenRefusedWithoutCitations() {
        assertThat(AnswerQualityScorer.score(
            refusal(), List.of(), "Insufficient evidence to answer.").passed()).isTrue();
        assertThat(AnswerQualityScorer.score(
            refusal(), List.of("annual.pdf"), "The salary was 1.2 million.").passed()).isFalse();
        assertThat(AnswerQualityScorer.score(
            refusal(), List.of(), "The documents do not say.").passed()).isFalse();
    }

    @Test
    void summaryAggregatesTheMetricsAndFailsWhenACaseFails() {
        Summary summary = AnswerQualityScorer.summarize(List.of(
            AnswerQualityScorer.score(answerable(), List.of("annual.pdf", "notes.pdf"),
                "Revenue grew 42 percent."),
            AnswerQualityScorer.score(answerable(), List.of(), "No idea."),
            AnswerQualityScorer.score(refusal(), List.of(), "Insufficient evidence to answer.")
        ));

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.answerable()).isEqualTo(2);
        assertThat(summary.refusalCases()).isEqualTo(1);
        assertThat(summary.expectedDocumentHitRate()).isEqualTo(0.5);
        assertThat(summary.citationPrecision()).isEqualTo(0.5); // 1 expected of 2 cited
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0);
        assertThat(summary.factMentionRate()).isEqualTo(0.5);
        assertThat(summary.passed()).isFalse();
        assertThat(summary.failures()).hasSize(1);
    }

    @Test
    void allPassingCasesPassTheSummary() {
        Summary summary = AnswerQualityScorer.summarize(List.of(
            AnswerQualityScorer.score(answerable(), List.of("annual.pdf"), "Revenue grew 42 percent."),
            AnswerQualityScorer.score(refusal(), List.of(), "Insufficient evidence to answer.")
        ));

        assertThat(summary.passed()).isTrue();
        assertThat(summary.failures()).isEmpty();
    }

    @Test
    void whitespaceAndCaseAreNormalizedBeforeMatching() {
        assertThat(AnswerQualityScorer.score(
            answerable(), List.of("annual.pdf"), "Revenue grew 42\nPERCENT.").passed()).isTrue();
    }
}
