package com.verityai.repository;

import com.verityai.entity.Document;
import com.verityai.entity.DocumentChunk;
import com.verityai.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level FTS tests against a real PostgreSQL container with the
 * Flyway migrations applied (V3 verity_or_tsquery function, generated
 * content_tsv column). Verifies the retrieval contract the RAG pipeline
 * depends on: natural questions match, stopwords do not, results are
 * rank-ordered and strictly scoped to the owning user's live documents.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DocumentChunkRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;

    private User createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ12");
        user.setRole("USER");
        return userRepository.save(user);
    }

    private Document createDocument(User owner, String filename) {
        Document doc = new Document();
        doc.setUser(owner);
        doc.setOriginalFilename(filename);
        doc.setStoredFilename("stored-" + filename);
        doc.setMimeType("application/pdf");
        doc.setSizeBytes(2048L);
        doc.setSha256("a".repeat(64));
        doc.setStatus(Document.DocumentStatus.READY);
        return documentRepository.save(doc);
    }

    private UUID createChunk(Document doc, int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(doc);
        chunk.setPageNumber(1);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunkRepository.save(chunk).getId();
    }

    private UUID idOf(Object[] row) {
        return (UUID) row[0];
    }

    private double rankOf(Object[] row) {
        return ((Number) row[1]).doubleValue();
    }

    @Test
    void naturalQuestionMatchesChunkEvenWhenSomeQueryTermsAreMissing() {
        User user = createUser("alice@example.com");
        Document doc = createDocument(user, "report.pdf");
        UUID chunkId = createChunk(doc, 0,
            "The company reported revenue of 4.2 billion dollars in fiscal year 2025.");

        List<Object[]> rows = chunkRepository.searchIdsWithRank(
            user.getId(), "What was the revenue growth in 2024?", 10);

        assertThat(rows).hasSize(1);
        assertThat(idOf(rows.get(0))).isEqualTo(chunkId);
        assertThat(rankOf(rows.get(0))).isGreaterThan(0.0);
    }

    @Test
    void stopwordOnlyQueryReturnsNoResults() {
        User user = createUser("bob@example.com");
        Document doc = createDocument(user, "report.pdf");
        createChunk(doc, 0, "Revenue grew sharply this year according to filings.");

        assertThat(chunkRepository.searchIdsWithRank(user.getId(), "the and of", 10)).isEmpty();
        assertThat(chunkRepository.searchIdsWithRank(user.getId(), "   ", 10)).isEmpty();
    }

    @Test
    void resultsAreOrderedByRankDescending() {
        User user = createUser("carol@example.com");
        Document doc = createDocument(user, "report.pdf");
        UUID weak = createChunk(doc, 0, "revenue only appears in this chunk");
        UUID strong = createChunk(doc, 1, "revenue and growth both appear in this chunk");

        List<Object[]> rows = chunkRepository.searchIdsWithRank(user.getId(), "revenue growth", 10);

        assertThat(rows).hasSize(2);
        assertThat(idOf(rows.get(0))).isEqualTo(strong);
        assertThat(idOf(rows.get(1))).isEqualTo(weak);
        assertThat(rankOf(rows.get(0))).isGreaterThan(rankOf(rows.get(1)));
    }

    @Test
    void scopedSearchOnlyReturnsChunksFromRequestedDocuments() {
        User user = createUser("dave@example.com");
        Document docA = createDocument(user, "a.pdf");
        Document docB = createDocument(user, "b.pdf");
        createChunk(docA, 0, "revenue analysis of segment alpha");
        UUID chunkB = createChunk(docB, 0, "revenue analysis of segment beta");

        List<Object[]> all = chunkRepository.searchIdsWithRank(user.getId(), "revenue", 10);
        assertThat(all).hasSize(2);

        List<Object[]> scoped = chunkRepository.searchIdsWithRankScoped(
            user.getId(), "revenue", List.of(docB.getId()), 10);

        assertThat(scoped).hasSize(1);
        assertThat(idOf(scoped.get(0))).isEqualTo(chunkB);
    }

    @Test
    void searchExcludesOtherUsersChunks() {
        User alice = createUser("alice2@example.com");
        User bob = createUser("bob2@example.com");
        Document aliceDoc = createDocument(alice, "alice.pdf");
        Document bobDoc = createDocument(bob, "bob.pdf");
        UUID aliceChunk = createChunk(aliceDoc, 0, "confidential revenue figures");
        createChunk(bobDoc, 0, "confidential revenue figures");

        List<Object[]> rows = chunkRepository.searchIdsWithRank(
            alice.getId(), "confidential revenue", 10);

        assertThat(rows).hasSize(1);
        assertThat(idOf(rows.get(0))).isEqualTo(aliceChunk);
    }

    @Test
    void searchExcludesSoftDeletedDocuments() {
        User user = createUser("erin@example.com");
        Document doc = createDocument(user, "gone.pdf");
        createChunk(doc, 0, "revenue will not be found after deletion");
        doc.setDeletedAt(Instant.now());
        documentRepository.save(doc);

        assertThat(chunkRepository.searchIdsWithRank(user.getId(), "revenue", 10)).isEmpty();
    }
}
