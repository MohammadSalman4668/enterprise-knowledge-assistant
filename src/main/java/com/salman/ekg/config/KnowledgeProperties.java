package com.salman.ekg.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All tunable application settings under the {@code ekg.*} prefix.
 * Secrets and personal details (DB password, the bootstrap admin's name, email and password) are
 * only ever read from the environment or the git-ignored {@code local.properties}.
 */
@ConfigurationProperties(prefix = "ekg")
public record KnowledgeProperties(
        Ingestion ingestion,
        Retrieval retrieval,
        Agent agent,
        Llm llm,
        Embedding embedding,
        Seed seed,
        Admin admin) {

    /**
     * Upload limits and chunking. Chunk sizes are approximate <em>tokens</em> (see
     * {@code com.salman.ekg.ingestion.TokenCounter}), not characters.
     *
     * @param chunkMinTokens     a chunk keeps absorbing following paragraphs/sections until it reaches this size
     * @param chunkMaxTokens     hard upper bound; longer paragraphs are split at sentences (then words)
     * @param chunkOverlapTokens closing text of a chunk repeated at the start of the next chunk of the same section
     * @param chunkTokenizer     {@code approximate} (words, numbers, punctuation; long words count twice)
     *                           or {@code whitespace}
     */
    public record Ingestion(long maxBytes,
                            @DefaultValue("350") int chunkMinTokens,
                            @DefaultValue("400") int chunkMaxTokens,
                            @DefaultValue("50") int chunkOverlapTokens,
                            @DefaultValue("approximate") String chunkTokenizer) {}

    /** Time budget for each retriever (keyword and semantic run in parallel). */
    public record Retrieval(Duration timeout, int defaultLimit) {}

    /**
     * Semantic search with a local Ollama embedding model.
     *
     * @param minSimilarity cosine similarity at which a passage counts as evidence by meaning
     *                      (calibrated on the seed corpus with nomic-embed-text)
     * @param queryPrefix / documentPrefix task prefixes the model expects (nomic: "search_query:", "search_document:")
     * @param asyncIndexing embed new chunks in the background after upload/re-index (tests: false)
     */
    public record Embedding(boolean enabled, String model, String baseUrl, Duration timeout, double minSimilarity,
                            String queryPrefix, String documentPrefix, int batchSize, boolean asyncIndexing) {}

    /**
     * Agent guard-rails: tool-call budget, evidence thresholds and the number of
     * passages read per sub-question.
     */
    public record Agent(int maxToolCalls, double minCoverage, int readsPerSubQuestion, int maxSubQuestions) {}

    /**
     * provider:
     * <ul>
     *   <li>{@code ollama} (default): a local Ollama server at {@code baseUrl}
     *       (e.g. http://localhost:11434/v1) running {@code model} (e.g. qwen2.5:3b);</li>
     *   <li>{@code simulated}: never call a model (deterministic, clearly labelled).</li>
     * </ul>
     * If Ollama is unreachable at request time, the agent falls back to the simulation and
     * marks the answer as degraded.
     */
    public record Llm(String provider, String model, Duration timeout, int maxTokens, String baseUrl) {

        public enum Provider { SIMULATED, OLLAMA }

        public Provider resolve() {
            return switch (provider == null ? "ollama" : provider.strip().toLowerCase()) {
                case "simulated" -> Provider.SIMULATED;
                case "ollama" -> {
                    if (baseUrl == null || baseUrl.isBlank()) {
                        throw new IllegalStateException("ekg.llm.provider=ollama requires ekg.llm.base-url");
                    }
                    if (model == null || model.isBlank()) {
                        throw new IllegalStateException("ekg.llm.provider=ollama requires ekg.llm.model");
                    }
                    yield Provider.OLLAMA;
                }
                default -> throw new IllegalStateException(
                        "Unknown ekg.llm.provider '" + provider + "'. Use 'ollama' or 'simulated'");
            };
        }
    }

    public record Seed(boolean enabled) {}

    /**
     * The first Admin, created (or refreshed) at every startup. It approves HR and other Admin
     * sign-ups. Set in local.properties: ekg.admin.name, ekg.admin.email, ekg.admin.password.
     */
    public record Admin(String name, String email, String password) {}
}
