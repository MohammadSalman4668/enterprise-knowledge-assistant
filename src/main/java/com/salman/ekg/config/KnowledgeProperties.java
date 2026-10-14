package com.salman.ekg.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunable application settings under the {@code ekg.*} prefix.
 * Secrets (DB password, API key, demo password) are only ever read from the
 * environment or the git-ignored {@code local.properties}.
 */
@ConfigurationProperties(prefix = "ekg")
public record KnowledgeProperties(
        Ingestion ingestion,
        Retrieval retrieval,
        Agent agent,
        Llm llm,
        Seed seed,
        Demo demo) {

    public record Ingestion(long maxBytes, int chunkSize) {}

    /** Per-retriever timeout; the keyword retriever is the last fallback in the chain. */
    public record Retrieval(Duration timeout, int defaultLimit) {}

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

    public record Demo(String password) {}
}
