package com.salman.ekg.retrieval;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.embedding.ChunkEmbeddingRepository;
import com.salman.ekg.embedding.EmbeddingService;

/**
 * Meaning-based retriever: embeds the query with the local Ollama embedding model and
 * ranks passages by cosine similarity.
 *
 * <p>Candidates are loaded with the <em>same</em> permission, status and current-version
 * rules as the keyword retriever, inside the SQL, so a forbidden or obsolete passage is
 * never scored. Similarity is then computed in Java over that filtered set (fine for this
 * corpus size; pgvector with an HNSW index is the scaling path).</p>
 */
@Component
@ConditionalOnProperty(name = "ekg.embedding.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticRetriever implements Retriever {

    /** Passages less similar than this are not returned at all (calibrated: off-topic queries scored <= 0.45). */
    static final double FLOOR_BELOW_THRESHOLD = 0.10;

    static final String SQL = """
            SELECT c.id AS chunk_id, d.id AS document_id, d.title, d.category, d.access_level,
                   c.version, c.chunk_index, c.section_ref, c.page_number, c.content, e.embedding
              FROM knowledge_chunk c
              JOIN knowledge_document d
                ON d.id = c.document_id
               AND c.version = d.current_version
              JOIN chunk_embedding e
                ON e.chunk_id = c.id
               AND e.model = :model
             WHERE c.superseded = FALSE
               AND d.status IN ('ACTIVE', 'NEEDS_REINDEX')
               AND d.access_level IN (SELECT ap.document_access_level
                                        FROM access_policy ap
                                       WHERE ap.user_role = :role)
               AND (CAST(:category AS VARCHAR) IS NULL OR lower(d.category) = lower(CAST(:category AS VARCHAR)))
               AND (CAST(:accessLevel AS VARCHAR) IS NULL OR d.access_level = CAST(:accessLevel AS VARCHAR))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final EmbeddingService embeddings;
    private final double floor;

    public SemanticRetriever(NamedParameterJdbcTemplate jdbc, EmbeddingService embeddings, KnowledgeProperties props) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.floor = props.embedding().minSimilarity() - FLOOR_BELOW_THRESHOLD;
    }

    @Override
    public String name() {
        return RetrievedChunk.SEMANTIC;
    }

    @Override
    public List<RetrievedChunk> retrieve(Query query) {
        if (query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        float[] q = embeddings.embedQuery(query.text());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("model", embeddings.model())
                .addValue("role", query.role().name())
                .addValue("category", query.category() == null || query.category().isBlank() ? null : query.category().strip(), Types.VARCHAR)
                .addValue("accessLevel", query.accessLevel() == null ? null : query.accessLevel().name(), Types.VARCHAR);
        List<RetrievedChunk> scored = new ArrayList<>();
        jdbc.query(SQL, params, rs -> {
            double sim = EmbeddingService.dot(q, ChunkEmbeddingRepository.readVector(rs, rs.findColumn("embedding")));
            if (sim >= floor) {
                scored.add(new RetrievedChunk(rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getString("title"),
                        rs.getString("category"), AccessLevel.valueOf(rs.getString("access_level")), rs.getInt("version"),
                        rs.getInt("chunk_index"), rs.getString("section_ref"), (Integer) rs.getObject("page_number"),
                        rs.getString("content"), sim, sim, RetrievedChunk.SEMANTIC));
            }
        });
        scored.sort(Comparator.comparingDouble(RetrievedChunk::similarity).reversed());
        return scored.size() > query.limit() ? List.copyOf(scored.subList(0, query.limit())) : scored;
    }
}
