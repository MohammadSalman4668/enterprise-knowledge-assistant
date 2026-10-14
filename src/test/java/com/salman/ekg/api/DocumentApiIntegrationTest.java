package com.salman.ekg.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.salman.ekg.domain.KnowledgeChunk;
import com.salman.ekg.repository.KnowledgeChunkRepository;
import com.salman.ekg.support.AbstractIntegrationTest;

class DocumentApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KnowledgeChunkRepository chunks;

    private static final String NEW_DOC = """
            # Parking Policy

            ## Visitor Parking

            Visitors may park in bays V1 to V12 for up to 4 hours. Register the vehicle plate at reception.
            """;

    /** HR uploads (pending) and Admin approves: the full approval-gate path. */
    private long uploadAndApprove(String name, String content, String category, String level) throws Exception {
        MvcResult created = mvc.perform(upload(file(name, content), "Parking Policy", category, level).with(HR))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL")).andReturn();
        long id = ((Number) json(created, "$.id")).longValue();
        mvc.perform(post("/api/knowledge/documents/{id}/approve", id).with(ADMIN)).andExpect(status().isOk());
        return id;
    }

    @Test
    @DisplayName("TC-05-01: supported non-empty upload is indexed (with doc + version on every chunk) and searchable after approval")
    void tc01_uploadIndexedAndSearchable() throws Exception {
        MvcResult created = mvc.perform(upload(file("parking.md", NEW_DOC), "Parking Policy", "Facilities", "INTERNAL").with(HR))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/knowledge/documents/")))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contentType").value("text/markdown"))
                .andReturn();
        long id = ((Number) json(created, "$.id")).longValue();

        List<KnowledgeChunk> stored = chunks.findByDocumentIdAndVersionOrderByChunkIndex(id, 1);
        assertThat(stored).isNotEmpty();
        assertThat(stored).allSatisfy(c -> {
            assertThat(c.getVersion()).isEqualTo(1);
            assertThat(c.getSectionRef()).isEqualTo("Parking Policy > Visitor Parking");
        });

        // Approval gate: indexed but not yet searchable
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking bays")).with(EMPLOYEE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        mvc.perform(post("/api/knowledge/documents/{id}/approve", id).with(ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking bays")).with(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].documentId").value(id))
                .andExpect(jsonPath("$.results[0].version").value(1))
                .andExpect(jsonPath("$.results[0].sectionRef").value("Parking Policy > Visitor Parking"))
                .andExpect(jsonPath("$.retrievalMode").value("HYBRID"));
    }

    @Test
    @DisplayName("Admin uploads skip the approval queue: active and searchable immediately; approving again is rejected")
    void adminUploadIsLiveImmediately() throws Exception {
        MvcResult created = mvc.perform(upload(file("parking.md", NEW_DOC), "Parking Policy", "Facilities", "INTERNAL").with(ADMIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        long id = ((Number) json(created, "$.id")).longValue();
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking bays")).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[0].documentId").value(id));
        mvc.perform(post("/api/knowledge/documents/{id}/approve", id).with(ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("TC-05-02: unsupported type, empty file, whitespace-only file and disguised binary are rejected")
    void tc02_unsupportedOrEmptyRejected() throws Exception {
        mvc.perform(upload(file("tool.exe", "MZ binary"), "Tool", "IT", "INTERNAL").with(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        mvc.perform(upload(file("empty.txt", new byte[0]), "Empty", "IT", "INTERNAL").with(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_DOCUMENT"));
        mvc.perform(upload(file("blank.md", "   \n\n\t  "), "Blank", "IT", "INTERNAL").with(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_DOCUMENT"));
        mvc.perform(upload(file("fake.pdf", "not really a pdf"), "Fake", "IT", "INTERNAL").with(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        mvc.perform(upload(file("bin.txt", new byte[] {0x00, 0x01, 0x02, 0x41}), "Binary", "IT", "INTERNAL").with(ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        // Nothing was persisted
        mvc.perform(get("/api/knowledge/documents").param("category", "IT").with(ADMIN))
                .andExpect(jsonPath("$.content[*].title", Matchers.not(Matchers.hasItems("Tool", "Empty", "Blank", "Fake", "Binary"))));
    }

    @Test
    @DisplayName("Upload validation: missing metadata gives VALIDATION_FAILED with field errors")
    void uploadMetadataValidation() throws Exception {
        mvc.perform(multipart("/api/knowledge/documents").file(file("a.md", "# A\n\ntext here")).param("title", " ").with(ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItems("title", "category", "accessLevel")));
    }

    @Test
    @DisplayName("Duplicate content is rejected with 409")
    void duplicateRejected() throws Exception {
        uploadAndApprove("parking.md", NEW_DOC, "Facilities", "INTERNAL");
        mvc.perform(upload(file("copy.md", NEW_DOC), "Copy", "Facilities", "INTERNAL").with(ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"));
    }

    @Test
    @DisplayName("RBAC: employees cannot upload; uploaders cannot use a level they cannot read; only admins approve")
    void roleRules() throws Exception {
        mvc.perform(upload(file("x.md", "# X\n\nSome text"), "X", "HR", "INTERNAL").with(EMPLOYEE))
                .andExpect(status().isForbidden());
        mvc.perform(upload(file("x.md", "# X\n\nSome text"), "X", "IT", "CONFIDENTIAL").with(HR))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_LEVEL_NOT_PERMITTED"));
        MvcResult created = mvc.perform(upload(file("x.md", "# X\n\nSome text"), "X", "IT", "INTERNAL").with(HR))
                .andExpect(status().isCreated()).andReturn();
        long id = ((Number) json(created, "$.id")).longValue();
        mvc.perform(post("/api/knowledge/documents/{id}/approve", id).with(HR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledge/documents/{id}", id)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("TC-05-06: metadata filters return only matching documents the caller may access")
    void tc06_metadataFiltersRespectAccess() throws Exception {
        // Category filter on search
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "days", "category", "HR")).with(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.results[*].category", Matchers.everyItem(Matchers.is("HR"))))
                .andExpect(jsonPath("$.results[*].accessLevel", Matchers.everyItem(Matchers.in(List.of("PUBLIC", "INTERNAL")))));
        // The same HR search as HR also finds the RESTRICTED salary bands; as employee it never appears
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "salary band engineers", "category", "HR")).with(HR))
                .andExpect(jsonPath("$.results[*].title", Matchers.hasItem("Engineering Salary Bands 2026")));
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "salary band engineers", "category", "HR")).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[*].title", Matchers.not(Matchers.hasItem("Engineering Salary Bands 2026"))));
        // Filtering on a level you cannot read yields nothing (not an error that confirms existence)
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "salary", "accessLevel", "RESTRICTED")).with(EMPLOYEE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        // Access-level filter
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "portal", "accessLevel", "PUBLIC")).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[*].accessLevel", Matchers.everyItem(Matchers.is("PUBLIC"))));
        // Document listing with category + status filters
        mvc.perform(get("/api/knowledge/documents").param("category", "engineering").param("status", "ACTIVE").with(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", Matchers.contains("Production Deployment Runbook")));
        mvc.perform(get("/api/knowledge/documents").param("category", "engineering").with(ADMIN))
                .andExpect(jsonPath("$.content[*].title",
                        Matchers.containsInAnyOrder("Production Deployment Runbook", "Incident Response Runbook")));
    }

    @Test
    @DisplayName("Restricted document detail and chunks are 404 for unauthorised roles")
    void restrictedDocumentNotVisible() throws Exception {
        MvcResult list = mvc.perform(get("/api/knowledge/documents").param("accessLevel", "RESTRICTED").with(ADMIN)).andReturn();
        int id = json(list, "$.content[0].id");
        mvc.perform(get("/api/knowledge/documents/{id}", id).with(EMPLOYEE)).andExpect(status().isNotFound());
        MvcResult detail = mvc.perform(get("/api/knowledge/documents/{id}", id).with(HR)).andExpect(status().isOk()).andReturn();
        int chunkId = json(detail, "$.chunks[0].chunkId");
        mvc.perform(get("/api/knowledge/chunks/{id}", chunkId).with(EMPLOYEE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge/chunks/{id}", chunkId).with(HR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true));
    }

    @Test
    @DisplayName("TC-05-09: re-indexing updated content serves only the new version; obsolete text is flagged and excluded")
    void tc09_reindexServesNewVersionOnly() throws Exception {
        long id = uploadAndApprove("parking.md", NEW_DOC, "Facilities", "INTERNAL");
        MvcResult before = mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking hours")).with(EMPLOYEE)).andReturn();
        int oldChunkId = json(before, "$.results[0].chunkId");

        String updated = NEW_DOC.replace("up to 4 hours", "up to 2 hours");
        mvc.perform(multipart("/api/knowledge/documents/{id}/revisions", id).file(file("parking-v2.md", updated)).with(ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_REINDEX"))
                .andExpect(jsonPath("$.version").value(1));

        // Until re-indexed, the approved v1 is still what is served
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking hours")).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[0].snippet", Matchers.containsString("4 hours")));
        mvc.perform(get("/api/knowledge/analytics/reindex-queue").with(ADMIN))
                .andExpect(jsonPath("$[*].id", Matchers.hasItem((int) id)));

        // Stale client: expectedVersion mismatch is a 409
        mvc.perform(put("/api/knowledge/documents/{id}/reindex", id).param("expectedVersion", "7").with(ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mvc.perform(put("/api/knowledge/documents/{id}/reindex", id).param("expectedVersion", "1").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Other documents may also match "hours"; what matters is that this document only appears as v2.
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking hours")).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[0].documentId").value(id))
                .andExpect(jsonPath("$.results[?(@.documentId == " + id + ")].version", Matchers.everyItem(Matchers.is(2))))
                .andExpect(jsonPath("$.results[0].version").value(2))
                .andExpect(jsonPath("$.results[0].snippet", Matchers.containsString("2 hours")))
                .andExpect(jsonPath("$.results[*].chunkId", Matchers.not(Matchers.hasItem(oldChunkId))));

        Map<String, Object> answer = ask("How long can visitors park?", EMPLOYEE);
        assertThat((String) answer.get("answer")).contains("2 hours").doesNotContain("4 hours");

        // Old evidence stays inspectable but is marked obsolete
        mvc.perform(get("/api/knowledge/chunks/{id}", oldChunkId).with(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(false))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.documentCurrentVersion").value(2));
        mvc.perform(get("/api/knowledge/documents/{id}", id).with(ADMIN))
                .andExpect(jsonPath("$.versions[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.versions[1].status").value("SUPERSEDED"));
    }

    @Test
    @DisplayName("Lifecycle: invalid transitions are 409; archived documents leave search")
    void lifecycleTransitions() throws Exception {
        long id = uploadAndApprove("parking.md", NEW_DOC, "Facilities", "INTERNAL");
        mvc.perform(post("/api/knowledge/documents/{id}/approve", id).with(ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        mvc.perform(multipart("/api/knowledge/documents/{id}/revisions", id).file(file("same.md", NEW_DOC)).with(ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CHANGES"));
        mvc.perform(post("/api/knowledge/documents/{id}/archive", id).with(ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking")).with(ADMIN))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(put("/api/knowledge/documents/{id}/reindex", id).with(ADMIN))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Search request validation returns field errors")
    void searchValidation() throws Exception {
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "", "limit", 500)).with(EMPLOYEE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItems("query", "limit")));
        mvc.perform(post("/api/knowledge/search").contentType("application/json").content("{not json").with(EMPLOYEE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
