package com.salman.ekg.retrieval;

import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.text.TextAnalysis;

/**
 * PostgreSQL full-text retriever (GIN-indexed {@code tsvector}, {@code ts_rank_cd}).
 * This is the fallback that is always available, with no model or embeddings needed.
 *
 * <p>Permission filtering happens <em>inside</em> the SQL by joining the
 * {@code access_policy} table on the caller's role, together with the version and status
 * rules. A forbidden or obsolete chunk is therefore never even read into memory.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class KeywordRetriever implements Retriever {

    private static final int MAX_TERMS = 16;

    static final String SQL = """
            SELECT c.id AS chunk_id, d.id AS document_id, d.title, d.category, d.access_level,
                   c.version, c.chunk_index, c.section_ref, c.page_number, c.content,
                   ts_rank_cd(c.search_vector, q.query, 32) AS score
              FROM knowledge_chunk c
              JOIN knowledge_document d
                ON d.id = c.document_id
               AND c.version = d.current_version
             CROSS JOIN to_tsquery('english', :tsquery) AS q(query)
             WHERE c.superseded = FALSE
               AND d.status IN ('ACTIVE', 'NEEDS_REINDEX')
               AND d.access_level IN (SELECT ap.document_access_level
                                        FROM access_policy ap
                                       WHERE ap.user_role = :role)
               AND (CAST(:category AS VARCHAR) IS NULL OR lower(d.category) = lower(CAST(:category AS VARCHAR)))
               AND (CAST(:accessLevel AS VARCHAR) IS NULL OR d.access_level = CAST(:accessLevel AS VARCHAR))
               AND c.search_vector @@ q.query
             ORDER BY score DESC, c.id
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public KeywordRetriever(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "KEYWORD";
    }

    @Override
    public List<RetrievedChunk> retrieve(Query query) {
        String tsquery = toTsQuery(query.text());
        if (tsquery.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tsquery", tsquery)
                .addValue("role", query.role().name())
                .addValue("category", blankToNull(query.category()), Types.VARCHAR)
                .addValue("accessLevel", query.accessLevel() == null ? null : query.accessLevel().name(), Types.VARCHAR)
                .addValue("limit", query.limit());
        return jdbc.query(SQL, params, (rs, i) -> new RetrievedChunk(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getString("title"),
                rs.getString("category"),
                AccessLevel.valueOf(rs.getString("access_level")),
                rs.getInt("version"),
                rs.getInt("chunk_index"),
                rs.getString("section_ref"),
                (Integer) rs.getObject("page_number"),
                rs.getString("content"),
                rs.getDouble("score")));
    }

    /**
     * Builds an OR query from sanitised terms. Tokens are restricted to letters/digits by
     * {@link TextAnalysis#tokens}, so no tsquery operator can be injected by the user.
     */
    static String toTsQuery(String text) {
        return TextAnalysis.searchTerms(text).stream()
                .limit(MAX_TERMS)
                .collect(Collectors.joining(" | "));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
