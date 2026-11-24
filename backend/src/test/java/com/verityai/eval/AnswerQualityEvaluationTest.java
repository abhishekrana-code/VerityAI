package com.verityai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verityai.dto.response.MessageResponse;
import com.verityai.entity.Conversation;
import com.verityai.entity.Document;
import com.verityai.entity.User;
import com.verityai.eval.AnswerQualityScorer.CaseResult;
import com.verityai.eval.AnswerQualityScorer.EvalCase;
import com.verityai.eval.AnswerQualityScorer.Summary;
import com.verityai.repository.ConversationRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import com.verityai.service.ai.ChatService;
import com.verityai.service.document.DocumentService;
import com.verityai.service.retrieval.RetrievalService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Live answer-quality evaluation. <b>Opt-in and cost-bearing</b>: it uploads a
 * small gold corpus through the real ingestion pipeline, asks the real model
 * through {@link ChatService}, and scores whether each answer cited the expected
 * document, whether the expected fact survived, and whether unanswerable
 * questions were refused.
 *
 * <p>Every other backend test mocks {@code LlmService}, so nothing there can
 * catch a prompt change, a model swap or a provider deprecation that quietly
 * degrades grounding. Run it before changing the RAG prompt or model:</p>
 *
 * <pre>{@code ./scripts/eval-answers.sh --run}</pre>
 *
 * <p>The corpus and questions are {@code src/test/resources/eval/*.json}. The
 * report — per-question answers, citations and latency, plus aggregate metrics —
 * is written to {@code backend/target/eval/answer-quality.json}.</p>
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads-eval",
    "verityai.summary.enabled=false",
    "verityai.knowledge.backfill-limit=0",
    "verityai.rate-limit.ai-requests-per-minute=0",
    "verityai.retrieval.top-k=8",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@Testcontainers
@EnabledIfEnvironmentVariable(named = "EVAL_ENABLED", matches = "true")
class AnswerQualityEvaluationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    @DynamicPropertySource
    static void evalProperties(DynamicPropertyRegistry registry) {
        // Same default as production (local ONNX); EVAL_EMBEDDINGS=off makes the
        // run keyword-only and much faster on a fresh machine.
        registry.add("verityai.embeddings.provider", () -> env("EVAL_EMBEDDINGS", "onnx"));
        String model = env("EVAL_MODEL", "");
        if (!model.isBlank()) {
            registry.add("verityai.llm.model", () -> model);
        }
    }

    @Autowired private DocumentService documentService;
    @Autowired private ChatService chatService;
    @Autowired private RetrievalService retrievalService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private record CorpusDocument(String filename, String text) {
    }

    @Test
    void answersAreGroundedCitedAndHonestlyRefused() throws Exception {
        List<CorpusDocument> corpus = read("/eval/corpus.json", new TypeReference<>() {
        });
        List<EvalCase> questions = read("/eval/questions.json", new TypeReference<>() {
        });

        User user = createUser();
        Conversation conversation = createConversation(user);

        List<UUID> scope = new ArrayList<>();
        for (CorpusDocument document : corpus) {
            MockMultipartFile file = new MockMultipartFile(
                "file", document.filename(), "application/pdf", toPdf(document.text()));
            scope.add(documentService.upload(user.getId(), file).getId());
        }
        awaitProcessed(scope);

        List<CaseResult> results = new ArrayList<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        String usedModel = "";

        for (EvalCase evalCase : questions) {
            MessageResponse answer = chatService.ask(conversation, evalCase.question(), scope);
            List<String> cited = answer.getCitations() == null ? List.of()
                : answer.getCitations().stream().map(MessageResponse.CitationResponse::getDocumentName).toList();

            CaseResult result = AnswerQualityScorer.score(evalCase, cited, answer.getContent());
            results.add(result);

            String strategy = retrievalService.search(user.getId(), evalCase.question(), scope, 8).strategy();
            promptTokens += answer.getPromptTokens() == null ? 0 : answer.getPromptTokens();
            completionTokens += answer.getCompletionTokens() == null ? 0 : answer.getCompletionTokens();
            if (answer.getModel() != null && !answer.getModel().isBlank()) {
                usedModel = answer.getModel();
            }

            System.out.printf("%-24s %s  %s%n", result.id(), result.passed() ? "PASS" : "FAIL", result.detail());
            cases.add(caseReport(evalCase, answer, result, strategy));
        }

        Summary summary = AnswerQualityScorer.summarize(results);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("model", usedModel);
        report.put("embeddings", env("EVAL_EMBEDDINGS", "onnx"));
        report.put("documents", corpus.stream().map(CorpusDocument::filename).toList());
        report.put("promptTokens", promptTokens);
        report.put("completionTokens", completionTokens);
        report.put("summary", summary);
        report.put("cases", cases);

        Path reportPath = Paths.get("target", "eval", "answer-quality.json");
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        System.out.printf("%nAnswer quality: %d questions, doc-hit %.0f%%, citation precision %.0f%%, "
                + "fact mention %.0f%%, refusals %.0f%%%n",
            summary.total(), summary.expectedDocumentHitRate() * 100,
            summary.citationPrecision() * 100, summary.factMentionRate() * 100,
            summary.refusalAccuracy() * 100);
        System.out.println("Report: " + reportPath.toAbsolutePath());

        assertThat(summary.failures())
            .as("failing questions (report: %s)", reportPath.toAbsolutePath())
            .isEmpty();
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    private User createUser() {
        User user = new User();
        user.setEmail("eval-" + UUID.randomUUID().toString().substring(0, 8) + "@verityai.test");
        user.setPasswordHash("not-used-by-the-evaluation");
        user.setDisplayName("Answer quality eval");
        return userRepository.save(user);
    }

    private Conversation createConversation(User user) {
        Conversation conversation = new Conversation();
        conversation.setUser(user);
        conversation.setTitle("Answer quality evaluation");
        return conversationRepository.save(conversation);
    }

    private void awaitProcessed(List<UUID> ids) throws InterruptedException {
        long timeoutSeconds = (long) envDouble("EVAL_TIMEOUT_SECONDS", 180);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Document> documents = documentRepository.findAllById(ids);
            documents.stream()
                .filter(d -> d.getStatus() == Document.DocumentStatus.FAILED)
                .findFirst()
                .ifPresent(d -> fail("Corpus document " + d.getOriginalFilename()
                    + " failed to process: " + d.getErrorCode() + " " + d.getErrorMessage()));
            if (documents.size() == ids.size()
                && documents.stream().allMatch(d -> d.getStatus() == Document.DocumentStatus.READY)) {
                return;
            }
            Thread.sleep(500);
        }
        fail("Corpus documents were not READY within " + timeoutSeconds + "s");
    }

    // ------------------------------------------------------------------
    // Fixtures and reporting
    // ------------------------------------------------------------------

    private <T> T read(String resource, TypeReference<T> type) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return objectMapper.readValue(in, type);
        }
    }

    private Map<String, Object> caseReport(EvalCase evalCase, MessageResponse answer,
                                           CaseResult result, String strategy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", evalCase.id());
        row.put("question", evalCase.question());
        row.put("passed", result.passed());
        row.put("detail", result.detail());
        row.put("expectedDocuments", evalCase.expectedDocuments());
        row.put("citedDocuments", answer.getCitations() == null ? List.of()
            : answer.getCitations().stream().map(MessageResponse.CitationResponse::getDocumentName).toList());
        row.put("evidenceStatus", answer.getEvidenceStatus());
        row.put("retrievalStrategy", strategy);
        row.put("latencyMs", answer.getLatencyMs());
        row.put("answer", answer.getContent());
        return row;
    }

    /** Minimal text PDF: one page, Helvetica, wrapped lines. */
    private static byte[] toPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.setLeading(15f);
                content.newLineAtOffset(50, 720);
                for (int start = 0; start < text.length(); start += 85) {
                    content.showText(text.substring(start, Math.min(start + 85, text.length())));
                    content.newLine();
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double envDouble(String name, double fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
