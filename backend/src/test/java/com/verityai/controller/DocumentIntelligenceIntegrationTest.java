package com.verityai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 document-intelligence contract tests: list filtering and sort
 * whitelist, tags, collections, processing history, summaries lifecycle and
 * bulk delete — all against a real PostgreSQL container with Flyway applied.
 *
 * <p>Uploads use a minimal fake PDF: the async pipeline will legitimately
 * fail to parse it (FAILED status), which is exactly what the processing
 * history assertions rely on. Summary regeneration is therefore only asserted
 * through its validation guard (non-READY documents are rejected), so no LLM
 * call is ever made from tests.</p>
 */
@SpringBootTest(properties = {
    "verityai.upload.dir=target/test-uploads",
    "verityai.summary.enabled=false",
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
class DocumentIntelligenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg17");

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private record Csrf(Cookie cookie, String token) {}

    private String uniqueEmail() {
        return "di-" + UUID.randomUUID() + "@example.com";
    }

    private Cookie register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", PASSWORD, "displayName", "Doc Intel Test"))))
            .andExpect(status().isOk())
            .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie after register").isNotNull();
        return session;
    }

    private Csrf csrf(Cookie session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("XSRF-TOKEN cookie after /auth/csrf").isNotNull();
        String token = objectMapper
            .readTree(result.getResponse().getContentAsString())
            .get("token").asText();
        return new Csrf(xsrf, token);
    }

    /** Uploads a fake PDF and returns its document id from the response body. */
    private String upload(Cookie session, Csrf csrf, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", filename, "application/pdf",
            ("%PDF-1.4 fake content for " + filename).getBytes(StandardCharsets.US_ASCII));
        MvcResult result = mockMvc.perform(multipart("/api/v1/documents")
                .file(file)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").exists())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    // ------------------------------------------------------------------
    // List: search filter, sort whitelist
    // ------------------------------------------------------------------

    @Test
    void listFiltersBySearchQuery() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        upload(session, csrf, "quarterly-report.pdf");
        upload(session, csrf, "unrelated-notes.pdf");

        mockMvc.perform(get("/api/v1/documents")
                .param("q", "quarterly")
                .cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].originalFilename").value("quarterly-report.pdf"));
    }

    @Test
    void listSortsByNameAscending() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        upload(session, csrf, "zebra-plan.pdf");
        upload(session, csrf, "alpha-plan.pdf");

        mockMvc.perform(get("/api/v1/documents")
                .param("sort", "name,asc")
                .cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].originalFilename").value("alpha-plan.pdf"))
            .andExpect(jsonPath("$.content[1].originalFilename").value("zebra-plan.pdf"));
    }

    @Test
    void listUnknownSortFieldFallsBackInsteadOfFailing() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        upload(session, csrf, "fallback.pdf");

        // A bogus sort key must never reach the JPA metamodel
        mockMvc.perform(get("/api/v1/documents")
                .param("sort", "DROP TABLE documents,asc")
                .cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listInvalidStatusReturnsValidationError() throws Exception {
        Cookie session = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/documents")
                .param("status", "NOT_A_STATUS")
                .cookie(session))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listExceedingPageSizeIsRejected() throws Exception {
        Cookie session = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/documents")
                .param("size", "500")
                .cookie(session))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    @Test
    void tagLifecycleCreateListAttachDetachDelete() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docId = upload(session, csrf, "tagged-doc.pdf");

        // Create
        MvcResult created = mockMvc.perform(post("/api/v1/tags")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Finance\",\"color\":\"#7c3aed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Finance"))
            .andExpect(jsonPath("$.documentCount").value(0))
            .andReturn();
        String tagId = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        // Duplicate name -> conflict
        mockMvc.perform(post("/api/v1/tags")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"finance\"}"))
            .andExpect(status().isConflict());

        // Attach to document, visible on the document and in the tag count
        mockMvc.perform(post("/api/v1/documents/" + docId + "/tags/" + tagId)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tags[0].name").value("Finance"));

        mockMvc.perform(get("/api/v1/tags").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].documentCount").value(1));

        // Tag filter returns the tagged document only
        mockMvc.perform(get("/api/v1/documents")
                .param("tag", tagId)
                .cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(docId));

        // Detach
        mockMvc.perform(delete("/api/v1/documents/" + docId + "/tags/" + tagId)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tags").doesNotExist());

        mockMvc.perform(get("/api/v1/tags").cookie(session))
            .andExpect(jsonPath("$[0].documentCount").value(0));

        // Delete tag
        mockMvc.perform(delete("/api/v1/tags/" + tagId)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tags").cookie(session))
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void tagAccessIsScopedToOwner() throws Exception {
        Cookie owner = register(uniqueEmail());
        Csrf ownerCsrf = csrf(owner);
        String other = uniqueEmail();
        Cookie intruder = register(other);
        Csrf intruderCsrf = csrf(intruder);

        MvcResult created = mockMvc.perform(post("/api/v1/tags")
                .cookie(owner, ownerCsrf.cookie())
                .header("X-XSRF-TOKEN", ownerCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Private\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String tagId = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        // The other user must not see or be able to delete the tag
        mockMvc.perform(get("/api/v1/tags").cookie(intruder))
            .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(delete("/api/v1/tags/" + tagId)
                .cookie(intruder, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token()))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------

    @Test
    void collectionLifecycleAndDocumentFiltering() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docA = upload(session, csrf, "collection-a.pdf");
        String docB = upload(session, csrf, "collection-b.pdf");

        MvcResult created = mockMvc.perform(post("/api/v1/collections")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Research\",\"description\":\"Phase 1 docs\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentCount").value(0))
            .andReturn();
        String collectionId = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        // Add one document
        mockMvc.perform(post("/api/v1/collections/" + collectionId + "/documents")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + docA + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentCount").value(1));

        // Collection filter on the document list
        mockMvc.perform(get("/api/v1/documents")
                .param("collection", collectionId)
                .cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(docA));

        // The other document reports no collections (batch enrichment)
        mockMvc.perform(get("/api/v1/documents/" + docB).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.collections").doesNotExist());

        // Membership listing + removal
        mockMvc.perform(get("/api/v1/collections/" + collectionId + "/documents").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentIds[0]").value(docA));

        mockMvc.perform(delete("/api/v1/collections/" + collectionId + "/documents/" + docA)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/collections/" + collectionId + "/documents").cookie(session))
            .andExpect(jsonPath("$.documentIds").isEmpty());

        // Rename, then delete
        mockMvc.perform(patch("/api/v1/collections/" + collectionId)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed Research\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed Research"));

        mockMvc.perform(delete("/api/v1/collections/" + collectionId)
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/collections").cookie(session))
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void collectionRejectsForeignDocument() throws Exception {
        Cookie owner = register(uniqueEmail());
        Csrf ownerCsrf = csrf(owner);
        String foreignDoc = upload(owner, ownerCsrf, "owned-by-owner.pdf");

        Cookie intruder = register(uniqueEmail());
        Csrf intruderCsrf = csrf(intruder);
        MvcResult created = mockMvc.perform(post("/api/v1/collections")
                .cookie(intruder, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sneaky\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String collectionId = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        mockMvc.perform(post("/api/v1/collections/" + collectionId + "/documents")
                .cookie(intruder, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + foreignDoc + "\"]}"))
            .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Processing history
    // ------------------------------------------------------------------

    @Test
    void processingHistoryRecordsUploadAndSurvivesReads() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docId = upload(session, csrf, "pipeline.pdf");

        MvcResult result = mockMvc.perform(
                get("/api/v1/documents/" + docId + "/processing").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value(docId))
            .andExpect(jsonPath("$.currentStage").isNotEmpty())
            .andExpect(jsonPath("$.currentAttempt").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andReturn();

        // The UPLOADED event was recorded synchronously at upload time. Async
        // pipeline stages may already be appended above it (newest-first), so
        // find it anywhere in the list rather than assuming position 0.
        var events = objectMapper.readTree(result.getResponse().getContentAsString()).get("events");
        var uploaded = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        events.forEach(e -> {
            if ("UPLOADED".equals(e.get("stage").asText())) uploaded.add(e);
        });
        assertThat(uploaded).as("UPLOADED event in processing history").isNotEmpty();
        assertThat(uploaded.get(0).get("attempt").asInt()).isEqualTo(1);
        assertThat(uploaded.get(0).get("createdAt").asText()).isNotEmpty();

        // History is persistent: a second read returns the same recorded event
        MvcResult again = mockMvc.perform(
                get("/api/v1/documents/" + docId + "/processing").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(again.getResponse().getContentAsString()).contains("UPLOADED");
    }

    @Test
    void processingHistoryIsInaccessibleToOtherUsers() throws Exception {
        Cookie owner = register(uniqueEmail());
        Csrf ownerCsrf = csrf(owner);
        String docId = upload(owner, ownerCsrf, "private-pipeline.pdf");

        Cookie intruder = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/documents/" + docId + "/processing").cookie(intruder))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Summaries
    // ------------------------------------------------------------------

    @Test
    void summariesEndpointReportsHonestNoneState() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docId = upload(session, csrf, "summarized.pdf");

        mockMvc.perform(get("/api/v1/documents/" + docId + "/summaries").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value(docId))
            .andExpect(jsonPath("$.summaryStatus").value("NONE"))
            .andExpect(jsonPath("$.summaries").isEmpty());
    }

    @Test
    void regenerateOnUnprocessedDocumentIsRejected() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docId = upload(session, csrf, "not-ready.pdf");

        // Fake PDF fails processing -> document never becomes READY, so
        // regeneration must be rejected before any AI budget is consumed.
        mockMvc.perform(post("/api/v1/documents/" + docId + "/summaries/regenerate")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void summariesEndpointIsScopedToOwner() throws Exception {
        Cookie owner = register(uniqueEmail());
        Csrf ownerCsrf = csrf(owner);
        String docId = upload(owner, ownerCsrf, "owner-summary.pdf");

        Cookie intruder = register(uniqueEmail());
        mockMvc.perform(get("/api/v1/documents/" + docId + "/summaries").cookie(intruder))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Bulk delete
    // ------------------------------------------------------------------

    @Test
    void bulkDeleteRemovesOwnedDocumentsAndReportsSkippedIds() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docA = upload(session, csrf, "bulk-a.pdf");
        String docB = upload(session, csrf, "bulk-b.pdf");
        UUID neverExisted = UUID.randomUUID();

        String body = objectMapper.writeValueAsString(
            Map.of("documentIds", java.util.List.of(docA, docB, neverExisted)));

        mockMvc.perform(post("/api/v1/documents/bulk-delete")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(2))
            .andExpect(jsonPath("$.skippedIds[0]").value(neverExisted.toString()));

        mockMvc.perform(get("/api/v1/documents").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void bulkDeleteRejectsEmptyAndOversizedIdLists() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);

        mockMvc.perform(post("/api/v1/documents/bulk-delete")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[]}"))
            .andExpect(status().isBadRequest());

        StringBuilder oversized = new StringBuilder("{\"documentIds\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) oversized.append(',');
            oversized.append('"').append(UUID.randomUUID()).append('"');
        }
        oversized.append("]}");

        mockMvc.perform(post("/api/v1/documents/bulk-delete")
                .cookie(session, csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized.toString()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void bulkDeleteNeverTouchesOtherUsersDocuments() throws Exception {
        Cookie owner = register(uniqueEmail());
        Csrf ownerCsrf = csrf(owner);
        String ownerDoc = upload(owner, ownerCsrf, "untouchable.pdf");

        Cookie intruder = register(uniqueEmail());
        Csrf intruderCsrf = csrf(intruder);

        mockMvc.perform(post("/api/v1/documents/bulk-delete")
                .cookie(intruder, intruderCsrf.cookie())
                .header("X-XSRF-TOKEN", intruderCsrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + ownerDoc + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(0))
            .andExpect(jsonPath("$.skippedIds[0]").value(ownerDoc));

        // The owner's document still exists
        mockMvc.perform(get("/api/v1/documents/" + ownerDoc).cookie(owner))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // File preview
    // ------------------------------------------------------------------

    @Test
    void filePreviewServesOnlyOwnedDocuments() throws Exception {
        Cookie session = register(uniqueEmail());
        Csrf csrf = csrf(session);
        String docId = upload(session, csrf, "preview.pdf");

        // Owner may fetch the stored file (the fake PDF is written to disk
        // even though text extraction later fails)
        mockMvc.perform(get("/api/v1/documents/" + docId + "/file").cookie(session))
            .andExpect(status().isOk());

        Cookie intruder = register(uniqueEmail());
        mockMvc.perform(get("/api/v1/documents/" + docId + "/file").cookie(intruder))
            .andExpect(status().isNotFound());
    }
}
