package com.verityai.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scores one answer against a gold expectation. Pure and deterministic: the
 * live evaluation supplies what the model actually answered, this class decides
 * whether it cited the right document, whether the expected fact survived, and
 * whether a refusal was honest.
 *
 * <p>Nothing here talks to a model, so the rules run in every CI build while the
 * expensive live run stays opt-in.</p>
 */
public final class AnswerQualityScorer {

    /** One row of {@code eval/questions.json}. */
    public record EvalCase(
        String id,
        String question,
        List<String> expectedDocuments,
        boolean shouldRefuse,
        List<String> mustMentionAny
    ) {}

    /** The outcome for one question, with the reason a failing case failed. */
    public record CaseResult(
        String id,
        String question,
        boolean shouldRefuse,
        boolean expectedDocumentHit,
        int citations,
        int unexpectedCitations,
        boolean refused,
        boolean mentionsExpectedFact,
        boolean passed,
        String detail
    ) {}

    /** Aggregate metrics for the report, plus the gate itself. */
    public record Summary(
        int total,
        int answerable,
        int refusalCases,
        double expectedDocumentHitRate,
        double citationPrecision,
        double refusalAccuracy,
        double factMentionRate,
        boolean passed,
        List<String> failures
    ) {}

    private AnswerQualityScorer() {
    }

    public static CaseResult score(EvalCase evalCase, List<String> citedDocuments, String answer) {
        List<String> expected = evalCase.expectedDocuments() == null
            ? List.of() : evalCase.expectedDocuments();
        List<String> cited = citedDocuments == null ? List.of() : citedDocuments;
        String text = normalize(answer);
        boolean refused = text.contains("insufficient evidence");

        if (evalCase.shouldRefuse()) {
            boolean passed = refused && cited.isEmpty();
            String detail = passed
                ? "refused without citations"
                : "expected a refusal with no citations, got " + cited.size()
                  + " citation(s)" + (refused ? "" : " and no refusal phrase");
            return new CaseResult(evalCase.id(), evalCase.question(), true, false,
                cited.size(), 0, refused, false, passed, detail);
        }

        boolean hit = cited.stream().anyMatch(expected::contains);
        long expectedCitations = cited.stream().filter(expected::contains).count();
        boolean mentions = mentionsAny(text, evalCase.mustMentionAny());
        boolean passed = hit && !cited.isEmpty() && !refused && mentions;

        String detail;
        if (passed) {
            detail = "cited " + expectedCitations + "/" + cited.size() + " expected document(s)";
        } else if (cited.isEmpty()) {
            detail = "answer cited nothing";
        } else if (!hit) {
            detail = "cited " + cited + ", expected one of " + expected;
        } else if (refused) {
            detail = "refused an answerable question";
        } else {
            detail = "answer did not mention any of " + evalCase.mustMentionAny();
        }
        return new CaseResult(evalCase.id(), evalCase.question(), false, hit,
            cited.size(), cited.size() - (int) expectedCitations, refused, mentions, passed, detail);
    }

    public static Summary summarize(List<CaseResult> results) {
        List<CaseResult> answerable = results.stream().filter(r -> !r.shouldRefuse()).toList();
        List<CaseResult> refusals = results.stream().filter(CaseResult::shouldRefuse).toList();

        List<String> failures = results.stream().filter(r -> !r.passed())
            .map(r -> r.id() + ": " + r.detail()).toList();

        int allCitations = answerable.stream().mapToInt(CaseResult::citations).sum();
        int expectedCitations = allCitations
            - answerable.stream().mapToInt(CaseResult::unexpectedCitations).sum();

        return new Summary(
            results.size(),
            answerable.size(),
            refusals.size(),
            rate(answerable.stream().filter(CaseResult::expectedDocumentHit).count(), answerable.size()),
            allCitations == 0 ? 0.0 : (double) expectedCitations / allCitations,
            rate(refusals.stream().filter(CaseResult::passed).count(), refusals.size()),
            rate(answerable.stream().filter(CaseResult::mentionsExpectedFact).count(), answerable.size()),
            failures.isEmpty(),
            failures
        );
    }

    private static double rate(long numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private static boolean mentionsAny(String normalizedAnswer, List<String> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return true;
        }
        return alternatives.stream()
            .map(AnswerQualityScorer::normalize)
            .anyMatch(normalizedAnswer::contains);
    }

    /** Case-insensitive, whitespace-collapsed text so "42\nPERCENT" matches "42 percent". */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
            .replace('\u00A0', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }
}
