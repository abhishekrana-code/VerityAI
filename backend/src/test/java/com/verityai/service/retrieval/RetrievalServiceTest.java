package com.verityai.service.retrieval;

import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.User;
import com.verityai.repository.DocumentChunkRepository;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval contract tests. These exercise the real native SQL (the user
 * filter and the document scope live in {@code DocumentChunkRepository}), so a
 * mocked service would prove nothing — removing {@code d.user_id = :userId}
 * must fail the isolation test.
 *
 * <p>Embeddings are off: the keyword branch is deterministic, which is exactly
 * what these assertions need.</p>
 */
@SpringBootTest(properties = {
    "verityai.embeddings.provider=off",
    "verityai.retrieval.top-k=2",
    "verityai.upload.dir=target/test-uploads-retrieval",
    "verityai.summary.enabled=false",
    "verityai.knowledge.enabled=false",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@Testcontainers
class RetrievalServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    @Autowired private RetrievalService retrievalService;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;

    private User user() {
        User user = new User();
        user.setEmail("retrieval-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("$2a$10$not-a-real-hash");
        user.setDisplayName("Retrieval Test");
        return userRepository.save(user);
    }

    private Document document(User owner, String filename) {
        Document doc = new Document();
        doc.setUser(owner);
        doc.setOriginalFilename(filename);
        doc.setStoredFilename("stored-" + filename);
        doc.setMimeType("application/pdf");
        doc.setSizeBytes(1024L);
        doc.setSha256((UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "")).substring(0, 64));
        doc.setStatus(Document.DocumentStatus.READY);
        return documentRepository.save(doc);
    }

    private DocumentChunk chunk(Document doc, int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(doc);
        chunk.setPageNumber(1);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunkRepository.save(chunk);
    }

    @Test
    void resultsAreScopedToTheRequestedDocuments() {
        User owner = user();
        Document included = document(owner, "included.pdf");
        Document excluded = document(owner, "excluded.pdf");
        DocumentChunk includedChunk = chunk(included, 0, "Glacier melt accelerated across the alpine basin.");
        DocumentChunk excludedChunk = chunk(excluded, 0, "Glacier melt accelerated across the polar shelf.");

        RetrievalService.SearchResult result =
            retrievalService.search(owner.getId(), "glacier melt", List.of(included.getId()), 10);

        assertThat(result.strategy()).isEqualTo("keyword");
        assertThat(result.chunks()).extracting(r -> r.chunk().getId())
            .containsExactly(includedChunk.getId());
        assertThat(result.chunks()).extracting(r -> r.chunk().getId())
            .doesNotContain(excludedChunk.getId());
    }

    @Test
    void userCannotRetrieveAnotherUsersChunks() {
        User alice = user();
        User bob = user();
        Document bobDocument = document(bob, "bob-only.pdf");
        DocumentChunk bobChunk = chunk(bobDocument, 0, "Turbine blade fatigue was measured at the coastal plant.");

        List<RetrievalService.RetrievedChunk> aliceResults =
            retrievalService.retrieve(alice.getId(), "turbine blade fatigue");
        List<RetrievalService.RetrievedChunk> scopedResults = retrievalService.retrieve(
            alice.getId(), "turbine blade fatigue", List.of(bobDocument.getId()));

        assertThat(aliceResults).isEmpty();
        assertThat(scopedResults)
            .as("document IDs supplied by the caller must never widen the user filter")
            .isEmpty();

        // The same query as Bob does find it, so the assertion above is about
        // ownership and not a query that simply matches nothing.
        List<RetrievalService.RetrievedChunk> bobResults =
            retrievalService.retrieve(bob.getId(), "turbine blade fatigue");
        assertThat(bobResults).extracting(r -> r.chunk().getId()).containsExactly(bobChunk.getId());
    }

    @Test
    void emptyEvidenceIsHandledWithoutErrors() {
        User owner = user();

        assertThat(retrievalService.retrieve(owner.getId(), "nothing matches this query")).isEmpty();
        assertThat(retrievalService.retrieve(owner.getId(), "   ")).isEmpty();

        RetrievalService.SearchResult blank = retrievalService.search(owner.getId(), "", null, 5);
        assertThat(blank.chunks()).isEmpty();
        assertThat(blank.strategy()).isEqualTo("keyword");
        assertThat(blank.model()).isNull();
    }

    @Test
    void rankingAndTopKRespectConfig() {
        User owner = user();
        Document doc = document(owner, "mining.pdf");
        // Four matching chunks; verityai.retrieval.top-k is 2 in this context.
        chunk(doc, 0, "Copper mining output rose in the northern district.");
        chunk(doc, 1, "Copper mining output fell in the southern district.");
        chunk(doc, 2, "Copper mining output was flat in the eastern district.");
        chunk(doc, 3, "Copper mining output doubled in the western district.");

        List<RetrievalService.RetrievedChunk> defaultTopK =
            retrievalService.retrieve(owner.getId(), "copper mining output");
        assertThat(defaultTopK).hasSize(2);
        assertThat(defaultTopK.get(0).rank()).isGreaterThanOrEqualTo(defaultTopK.get(1).rank());

        List<RetrievalService.RetrievedChunk> explicitLimit =
            retrievalService.search(owner.getId(), "copper mining output", null, 1).chunks();
        assertThat(explicitLimit).hasSize(1);
    }
}
