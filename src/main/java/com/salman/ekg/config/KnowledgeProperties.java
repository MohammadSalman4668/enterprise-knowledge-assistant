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
     * provider: {@code auto} (live when an API key is present), {@code simulated} or {@code anthropic}.
     */
    public record Llm(String provider, String apiKey, String model, Duration timeout, int maxTokens) {

        public boolean liveRequested() {
            boolean hasKey = apiKey != null && !apiKey.isBlank();
            return switch (provider == null ? "auto" : provider.toLowerCase()) {
                case "simulated" -> false;
                case "anthropic" -> {
                    if (!hasKey) {
                        throw new IllegalStateException("ekg.llm.provider=anthropic requires ANTHROPIC_API_KEY");
                    }
                    yield true;
                }
                default -> hasKey;
            };
        }
    }

    public record Seed(boolean enabled) {}

    public record Demo(String password) {}
}
