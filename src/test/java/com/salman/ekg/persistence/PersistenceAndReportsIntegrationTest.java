package com.salman.ekg.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.KnowledgeChunk;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.repository.AnalyticsRepository;
import com.salman.ekg.repository.KnowledgeChunkRepository;
import com.salman.ekg.repository.KnowledgeDocumentRepository;
import com.salman.ekg.support.AbstractIntegrationTest;

class PersistenceAndReportsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private AnalyticsRepository analytics;

    @Test
    @DisplayName("Seed corpus is fully indexed: every chunk belongs to the current version of its document")
    void seedIsIndexed() {
        List<KnowledgeDocument> all = documents.findAll();
        assertThat(all).hasSize(10).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
            assertThat(chunks.countByDocumentIdAndVersion(d.getId(), d.getCurrentVersion())).isPositive();
        });
        Integer orphanOrStale = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk c JOIN knowledge_document d ON d.id = c.document_id
                 WHERE c.superseded = FALSE AND c.version <> d.current_version""", Integer.class);
        assertThat(orphanOrStale).isZero();
    }

    @Test
    @DisplayName("Database constraints reject invalid data even if the service layer were bypassed")
    void constraintsEnforced() {
        KnowledgeDocument doc = documents.findAll().getFirst();
        KnowledgeChunk first = chunks.findByDocumentIdAndVersionOrderByChunkIndex(doc.getId(), 1).getFirst();
        // Duplicate (document, version, chunk_index)
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO knowledge_chunk (document_id, version, chunk_index, content, searchable_text, created_at)
                VALUES (?, 1, ?, 'x', 'x', now())""", doc.getId(), first.getChunkIndex()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Blank chunk content
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO knowledge_chunk (document_id, version, chunk_index, content, searchable_text, created_at)
                VALUES (?, 1, 999, '   ', 'x', now())""", doc.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Unknown status value
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_document SET status = 'DELETED' WHERE id = ?", doc.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Same live content twice (partial unique index)
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO knowledge_document (title, category, owner, access_level, status, file_name, content_type,
                                                content_hash, uploaded_at, updated_at)
                VALUES ('dup', 'HR', 'hr', 'INTERNAL', 'ACTIVE', 'd.md', 'text/markdown', ?, now(), now())""",
                doc.getContentHash()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Rating outside 1..5
        Long runId = jdbc.queryForObject("""
                INSERT INTO agent_run (username, user_role, question, answer, outcome, mode, degraded, trace_json, latency_ms, created_at)
                VALUES ('u', 'EMPLOYEE', 'q', 'a', 'ANSWERED', 'SIMULATED', false, '{}', 1, now()) RETURNING id""", Long.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO knowledge_feedback (agent_run_id, username, question, rating, created_at)
                VALUES (?, 'u', 'q', 9, now())""", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Repository re-validation drops forbidden and obsolete chunk ids")
    void filterLiveAndAccessible() {
        KnowledgeDocument restricted = documents.findAll().stream()
                .filter(d -> d.getAccessLevel() == AccessLevel.RESTRICTED).findFirst().orElseThrow();
        Long restrictedChunk = chunks.findByDocumentIdAndVersionOrderByChunkIndex(restricted.getId(), 1).getFirst().getId();
        KnowledgeDocument internal = documents.findAll().stream()
                .filter(d -> d.getAccessLevel() == AccessLevel.INTERNAL).findFirst().orElseThrow();
        Long internalChunk = chunks.findByDocumentIdAndVersionOrderByChunkIndex(internal.getId(), 1).getFirst().getId();

        Set<AccessLevel> employeeLevels = Set.of(AccessLevel.PUBLIC, AccessLevel.INTERNAL);
        assertThat(chunks.filterLiveAndAccessible(List.of(restrictedChunk, internalChunk), DocumentStatus.SEARCHABLE, employeeLevels))
                .containsExactly(internalChunk);

        jdbc.update("UPDATE knowledge_chunk SET superseded = TRUE WHERE id = ?", internalChunk);
        assertThat(chunks.filterLiveAndAccessible(List.of(internalChunk), DocumentStatus.SEARCHABLE, employeeLevels)).isEmpty();
    }

    @Test
    @DisplayName("SQL tasks 1-5 return correct aggregates")
    void reportQueries() throws Exception {
        // Generate some activity
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "leave days", "category", "HR")).with(EMPLOYEE));
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "vpn token")).with(EMPLOYEE));
        ask("How many days of annual leave do I get?", EMPLOYEE);

        // 1. grouped by category and status
        List<Map<String, Object>> grouped = analytics.documentsByCategoryAndStatus();
        assertThat(grouped).anySatisfy(r -> {
            assertThat(r.get("category")).isEqualTo("HR");
            assertThat(r.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) r.get("document_count")).intValue()).isEqualTo(3);
        });
        // 2. nothing awaiting re-index in a fresh corpus
        assertThat(analytics.awaitingReindex()).isEmpty();
        // 3. most searched categories: HR leads (filter search + agent search on leave)
        List<Map<String, Object>> searched = analytics.searchedCategories(10);
        assertThat(searched.getFirst().get("category")).isEqualTo("HR");
        assertThat(((Number) searched.getFirst().get("agent_searches")).intValue()).isPositive();
        // 4. no feedback yet
        assertThat(analytics.feedbackByDocument()).isEmpty();
        // 5. accessible documents per role follow the policy table
        assertThat(analytics.accessibleToRole("EMPLOYEE")).hasSize(8)
                .noneMatch(r -> Set.of("CONFIDENTIAL", "RESTRICTED").contains(r.get("access_level")));
        assertThat(analytics.accessibleToRole("HR")).hasSize(9)
                .noneMatch(r -> "CONFIDENTIAL".equals(r.get("access_level")));
        assertThat(analytics.accessibleToRole("ADMIN")).hasSize(10);
    }

    @Test
    @DisplayName("Analytics endpoints are admin-only")
    void analyticsAdminOnly() throws Exception {
        mvc.perform(get("/api/knowledge/analytics/summary").with(EMPLOYEE)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/knowledge/analytics/summary").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.searchable_documents").value(10));
        mvc.perform(get("/api/knowledge/analytics/accessible-documents").param("role", "HR").with(ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$", Matchers.hasSize(9)));
        mvc.perform(get("/api/knowledge/analytics/accessible-documents").param("role", "CEO").with(ADMIN))
                .andExpect(status().isBadRequest());
    }
}
