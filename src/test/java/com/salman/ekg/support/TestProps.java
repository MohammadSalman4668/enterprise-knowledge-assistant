package com.salman.ekg.support;

import java.time.Duration;

import com.salman.ekg.config.KnowledgeProperties;

/** Properties for plain unit tests (same values as application.properties, short timeouts). */
public final class TestProps {

    private TestProps() {
    }

    public static KnowledgeProperties defaults() {
        return withTimeouts(Duration.ofMillis(300), Duration.ofMillis(300));
    }

    public static KnowledgeProperties withTimeouts(Duration retrieval, Duration llm) {
        return new KnowledgeProperties(
                new KnowledgeProperties.Ingestion(5_242_880, 800),
                new KnowledgeProperties.Retrieval(retrieval, 10),
                new KnowledgeProperties.Agent(10, 0.6, 2, 3),
                new KnowledgeProperties.Llm("simulated", "test-model", llm, 512, ""),
                new KnowledgeProperties.Embedding(false, "fake", "", Duration.ofSeconds(5), 0.35,
                        "search_query:", "search_document:", 16, false),
                new KnowledgeProperties.Seed(false),
                new KnowledgeProperties.Admin("Test Admin", "admin@example.test", ""));
    }
}
