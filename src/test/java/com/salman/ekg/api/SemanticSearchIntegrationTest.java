package com.salman.ekg.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.salman.ekg.support.AbstractIntegrationTest;

/**
 * Hybrid (keyword + semantic) search end to end with the deterministic fake embedder
 * (see FakeEmbeddingConfig). Real-model quality is checked manually against Ollama.
 */
@SuppressWarnings("unchecked")
class SemanticSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Every live chunk is embedded after seeding, with the configured model")
    void allLiveChunksEmbedded() {
        Integer live = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk c JOIN knowledge_document d ON d.id = c.document_id
                 WHERE NOT c.superseded AND d.status NOT IN ('ARCHIVED', 'REJECTED')""", Integer.class);
        Integer embedded = jdbc.queryForObject("SELECT COUNT(*) FROM chunk_embedding", Integer.class);
        assertThat(embedded).isEqualTo(live).isPositive();
    }

    @Test
    @DisplayName("A question worded differently from the document ('vacation' vs 'annual leave') is found by meaning")
    void paraphraseFoundByMeaning() throws Exception {
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "How many vacation days do I get?")).with(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievalMode").value("HYBRID"))
                .andExpect(jsonPath("$.results[0].title").value("Annual Leave Policy"))
                .andExpect(jsonPath("$.results[0].matchedBy", Matchers.containsString("SEMANTIC")));

        Map<String, Object> res = ask("How many vacation days do I get?", EMPLOYEE);
        assertThat(res.get("outcome")).isNotEqualTo("ABSTAINED"); // answered by meaning (the fake embedder may add a lexical "conflict")
        assertThat((List<Map<String, Object>>) res.get("citations")).extracting(c -> c.get("title")).contains("Annual Leave Policy");
        assertThat((List<Map<String, Object>>) res.get("subQuestions")).first()
                .satisfies(s -> assertThat(((Number) s.get("bestSimilarity")).doubleValue()).isGreaterThan(0.20));
    }

    @Test
    @DisplayName("Semantic search respects permissions: an employee never gets the RESTRICTED salary bands by meaning")
    void semanticSearchRespectsPermissions() throws Exception {
        String query = "salary band engineers compensation";
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", query)).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[*].accessLevel", Matchers.not(Matchers.hasItem("RESTRICTED"))));
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", query)).with(HR))
                .andExpect(jsonPath("$.results[*].title", Matchers.hasItem("Engineering Salary Bands 2026")));
    }

    @Test
    @DisplayName("Re-indexing embeds the new version; semantic search returns only the current version")
    void reindexedContentIsEmbedded() throws Exception {
        String v1 = "# Parking Policy\n\n## Visitor Parking\n\nVisitors may park in bays V1 to V12 for up to 4 hours.\n";
        long id = ((Number) json(mvc.perform(upload(file("parking.md", v1), "Parking Policy", "Facilities", "INTERNAL")
                .with(ADMIN)).andExpect(status().isCreated()).andReturn(), "$.id")).longValue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/knowledge/documents/{id}/revisions", id)
                        .file(file("parking-v2.md", v1.replace("4 hours", "2 hours"))).with(ADMIN))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/knowledge/documents/{id}/reindex", id).with(ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        Integer currentEmbedded = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk c JOIN chunk_embedding e ON e.chunk_id = c.id
                 WHERE c.document_id = ? AND c.version = 2 AND NOT c.superseded""", Integer.class, id);
        assertThat(currentEmbedded).isPositive();
        mvc.perform(postJson("/api/knowledge/search", Map.of("query", "visitor parking duration", "category", "Facilities")).with(EMPLOYEE))
                .andExpect(jsonPath("$.results[*].version", Matchers.everyItem(Matchers.is(2))))
                .andExpect(jsonPath("$.results[0].snippet", Matchers.containsString("2 hours")));
    }
}
