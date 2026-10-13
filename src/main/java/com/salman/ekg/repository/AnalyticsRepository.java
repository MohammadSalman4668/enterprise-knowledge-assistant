package com.salman.ekg.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reporting SQL (SQL tasks 1-5). The same statements are in docs/sql/reports.sql so
 * they can be run directly in psql. Plain JDBC is used because these are read-only
 * aggregates, not entity graphs.
 */
@Repository
public class AnalyticsRepository {

    /** Task 1: documents grouped by category and status. */
    static final String DOCUMENTS_BY_CATEGORY_STATUS = """
            SELECT d.category, d.status, COUNT(*) AS document_count
              FROM knowledge_document d
             GROUP BY d.category, d.status
             ORDER BY d.category, d.status
            """;

    /** Task 2: documents awaiting re-indexing (a pending revision exists or status says so). */
    static final String AWAITING_REINDEX = """
            SELECT d.id, d.title, d.category, d.status, d.current_version,
                   v.version AS pending_version, v.created_by AS submitted_by, v.created_at AS submitted_at
              FROM knowledge_document d
              LEFT JOIN document_version v
                ON v.document_id = d.id AND v.status = 'PENDING'
             WHERE d.status = 'NEEDS_REINDEX' OR v.id IS NOT NULL
             ORDER BY v.created_at NULLS LAST, d.id
            """;

    /** Task 3: most frequently searched categories (explicit filter, else top hit's category). */
    static final String SEARCHED_CATEGORIES = """
            SELECT COALESCE(s.resolved_category, '(no results)') AS category,
                   COUNT(*) AS search_count,
                   SUM(CASE WHEN s.source = 'AGENT' THEN 1 ELSE 0 END) AS agent_searches,
                   SUM(CASE WHEN s.result_count = 0 THEN 1 ELSE 0 END) AS zero_result_searches
              FROM search_log s
             GROUP BY COALESCE(s.resolved_category, '(no results)')
             ORDER BY search_count DESC, category
             LIMIT :limit
            """;

    /** Task 4: feedback ratings by document. */
    static final String FEEDBACK_BY_DOCUMENT = """
            SELECT d.id AS document_id, d.title, d.category,
                   COUNT(f.id) AS feedback_count,
                   ROUND(AVG(f.rating)::numeric, 2) AS average_rating,
                   SUM(CASE WHEN f.rating <= 2 THEN 1 ELSE 0 END) AS low_ratings
              FROM knowledge_feedback f
              JOIN knowledge_document d ON d.id = f.document_id
             GROUP BY d.id, d.title, d.category
             ORDER BY average_rating ASC, feedback_count DESC
            """;

    /** Task 5: documents accessible to a given role (approved/searchable only). */
    static final String ACCESSIBLE_TO_ROLE = """
            SELECT d.id, d.title, d.category, d.access_level, d.status, d.current_version
              FROM knowledge_document d
              JOIN access_policy ap
                ON ap.document_access_level = d.access_level
             WHERE ap.user_role = :role
               AND d.status IN ('ACTIVE', 'NEEDS_REINDEX')
             ORDER BY d.category, d.title
            """;

    static final String SUMMARY = """
            SELECT
              (SELECT COUNT(*) FROM knowledge_document WHERE status IN ('ACTIVE', 'NEEDS_REINDEX')) AS searchable_documents,
              (SELECT COUNT(*) FROM knowledge_document WHERE status = 'PENDING_APPROVAL')           AS pending_approval,
              (SELECT COUNT(*) FROM knowledge_document WHERE status = 'NEEDS_REINDEX')              AS needs_reindex,
              (SELECT COUNT(*) FROM search_log WHERE source = 'SEARCH')                             AS searches,
              (SELECT COUNT(*) FROM agent_run)                                                      AS questions,
              (SELECT COUNT(*) FROM agent_run WHERE outcome = 'ABSTAINED')                          AS abstained,
              (SELECT COUNT(*) FROM agent_run WHERE degraded)                                       AS degraded_runs,
              (SELECT COUNT(*) FROM knowledge_feedback)                                             AS feedback_count,
              (SELECT ROUND(AVG(rating)::numeric, 2) FROM knowledge_feedback)                       AS average_rating
            """;

    static final String OUTCOMES = """
            SELECT outcome, COUNT(*) AS run_count FROM agent_run GROUP BY outcome ORDER BY outcome
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> documentsByCategoryAndStatus() {
        return jdbc.queryForList(DOCUMENTS_BY_CATEGORY_STATUS, Map.of());
    }

    public List<Map<String, Object>> awaitingReindex() {
        return jdbc.queryForList(AWAITING_REINDEX, Map.of());
    }

    public List<Map<String, Object>> searchedCategories(int limit) {
        return jdbc.queryForList(SEARCHED_CATEGORIES, new MapSqlParameterSource("limit", limit));
    }

    public List<Map<String, Object>> feedbackByDocument() {
        return jdbc.queryForList(FEEDBACK_BY_DOCUMENT, Map.of());
    }

    public List<Map<String, Object>> accessibleToRole(String role) {
        return jdbc.queryForList(ACCESSIBLE_TO_ROLE, new MapSqlParameterSource("role", role));
    }

    public Map<String, Object> summary() {
        return jdbc.queryForMap(SUMMARY, Map.of());
    }

    public List<Map<String, Object>> outcomes() {
        return jdbc.queryForList(OUTCOMES, Map.of());
    }
}
