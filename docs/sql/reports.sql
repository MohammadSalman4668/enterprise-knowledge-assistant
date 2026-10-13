-- =====================================================================
-- SQL tasks (identical to AnalyticsRepository; exposed at /api/knowledge/analytics/*)
-- Run with:  psql -U ekg_app -d ekg -v role=EMPLOYEE -f docs/sql/reports.sql
-- =====================================================================

-- 1. Documents grouped by category and status
SELECT d.category, d.status, COUNT(*) AS document_count
  FROM knowledge_document d
 GROUP BY d.category, d.status
 ORDER BY d.category, d.status;

-- 2. Documents awaiting re-indexing
--    (a submitted revision is PENDING, or the document is flagged NEEDS_REINDEX)
SELECT d.id, d.title, d.category, d.status, d.current_version,
       v.version AS pending_version, v.created_by AS submitted_by, v.created_at AS submitted_at
  FROM knowledge_document d
  LEFT JOIN document_version v
    ON v.document_id = d.id AND v.status = 'PENDING'
 WHERE d.status = 'NEEDS_REINDEX' OR v.id IS NOT NULL
 ORDER BY v.created_at NULLS LAST, d.id;

-- 3. Most frequently searched categories
--    resolved_category = explicit filter, otherwise the category of the top hit
SELECT COALESCE(s.resolved_category, '(no results)') AS category,
       COUNT(*) AS search_count,
       SUM(CASE WHEN s.source = 'AGENT' THEN 1 ELSE 0 END) AS agent_searches,
       SUM(CASE WHEN s.result_count = 0 THEN 1 ELSE 0 END) AS zero_result_searches
  FROM search_log s
 GROUP BY COALESCE(s.resolved_category, '(no results)')
 ORDER BY search_count DESC, category
 LIMIT 10;

-- 4. Feedback ratings by document
SELECT d.id AS document_id, d.title, d.category,
       COUNT(f.id) AS feedback_count,
       ROUND(AVG(f.rating)::numeric, 2) AS average_rating,
       SUM(CASE WHEN f.rating <= 2 THEN 1 ELSE 0 END) AS low_ratings
  FROM knowledge_feedback f
  JOIN knowledge_document d ON d.id = f.document_id
 GROUP BY d.id, d.title, d.category
 ORDER BY average_rating ASC, feedback_count DESC;

-- 5. Documents accessible to a given role (searchable documents only)
SELECT d.id, d.title, d.category, d.access_level, d.status, d.current_version
  FROM knowledge_document d
  JOIN access_policy ap
    ON ap.document_access_level = d.access_level
 WHERE ap.user_role = :'role'
   AND d.status IN ('ACTIVE', 'NEEDS_REINDEX')
 ORDER BY d.category, d.title;
