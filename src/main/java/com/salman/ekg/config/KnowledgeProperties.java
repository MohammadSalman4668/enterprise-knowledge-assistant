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
     *   <li>{@code auto} (default): Anthropic when an API key is present, otherwise the simulation;</li>
     *   <li>{@code simulated}: never call a model;</li>
     *   <li>{@code anthropic}: Anthropic Messages API (requires {@code apiKey});</li>
     *   <li>{@code openai-compatible} (alias {@code ollama}): any server exposing
     *       {@code POST {baseUrl}/chat/completions}, e.g. a local Ollama at http://localhost:11434/v1.
     *       {@code apiKey} is optional and sent as a Bearer token when set.</li>
     * </ul>
     */
    public record Llm(String provider, String apiKey, String model, Duration timeout, int maxTokens, String baseUrl) {

        public enum Provider { SIMULATED, ANTHROPIC, OPENAI_COMPATIBLE }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public Provider resolve() {
            return switch (provider == null ? "auto" : provider.strip().toLowerCase()) {
                case "simulated" -> Provider.SIMULATED;
                case "anthropic" -> {
                    if (!hasApiKey()) {
                        throw new IllegalStateException("ekg.llm.provider=anthropic requires ANTHROPIC_API_KEY or ekg.llm.api-key");
                    }
                    yield Provider.ANTHROPIC;
                }
                case "openai-compatible", "ollama" -> {
                    if (baseUrl == null || baseUrl.isBlank()) {
                        throw new IllegalStateException("ekg.llm.provider=" + provider + " requires ekg.llm.base-url");
                    }
                    yield Provider.OPENAI_COMPATIBLE;
                }
                default -> hasApiKey() ? Provider.ANTHROPIC : Provider.SIMULATED;
            };
        }
    }

    public record Seed(boolean enabled) {}

    public record Demo(String password) {}
}
