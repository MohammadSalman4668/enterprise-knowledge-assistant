package com.salman.ekg.embedding;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code chunk_embedding} (REAL[] vectors are not mapped as JPA entities). */
@Repository
public class ChunkEmbeddingRepository {

    public record ChunkText(long chunkId, String text) {}

    private final NamedParameterJdbcTemplate jdbc;

    public ChunkEmbeddingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Live chunks (not superseded, document not archived/rejected) that have no vector for {@code model} yet. */
    public List<ChunkText> findMissing(String model, int limit) {
        return jdbc.query("""
                SELECT c.id, c.searchable_text
                  FROM knowledge_chunk c
                  JOIN knowledge_document d ON d.id = c.document_id
                  LEFT JOIN chunk_embedding e ON e.chunk_id = c.id AND e.model = :model
                 WHERE c.superseded = FALSE
                   AND d.status NOT IN ('ARCHIVED', 'REJECTED')
                   AND e.chunk_id IS NULL
                 ORDER BY c.id
                 LIMIT :limit
                """, new MapSqlParameterSource("model", model).addValue("limit", limit),
                (rs, i) -> new ChunkText(rs.getLong(1), rs.getString(2)));
    }

    public void upsert(long chunkId, String model, float[] vector) {
        jdbc.getJdbcTemplate().update("""
                INSERT INTO chunk_embedding (chunk_id, model, dimensions, embedding, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (chunk_id) DO UPDATE
                   SET model = EXCLUDED.model, dimensions = EXCLUDED.dimensions,
                       embedding = EXCLUDED.embedding, created_at = EXCLUDED.created_at
                """, ps -> {
                    ps.setLong(1, chunkId);
                    ps.setString(2, model);
                    ps.setInt(3, vector.length);
                    ps.setArray(4, ps.getConnection().createArrayOf("float4", box(vector)));
                });
    }

    public Map<Long, float[]> findByChunkIds(Collection<Long> chunkIds, String model) {
        Map<Long, float[]> out = new HashMap<>();
        if (chunkIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT chunk_id, embedding FROM chunk_embedding WHERE model = :model AND chunk_id IN (:ids)",
                new MapSqlParameterSource("model", model).addValue("ids", chunkIds),
                rs -> {
                    out.put(rs.getLong(1), readVector(rs, 2));
                });
        return out;
    }

    public long countForModel(String model) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM chunk_embedding WHERE model = :model",
                new MapSqlParameterSource("model", model), Long.class);
        return n == null ? 0 : n;
    }

    public static float[] readVector(ResultSet rs, int column) throws SQLException {
        Array array = rs.getArray(column);
        Object raw = array.getArray();
        if (raw instanceof Float[] boxed) {
            float[] v = new float[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                v[i] = boxed[i];
            }
            return v;
        }
        if (raw instanceof float[] primitive) {
            return primitive;
        }
        Object[] values = (Object[]) raw;
        float[] v = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            v[i] = ((Number) values[i]).floatValue();
        }
        return v;
    }

    private static Float[] box(float[] v) {
        Float[] out = new Float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i];
        }
        return out;
    }
}
