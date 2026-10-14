package com.salman.ekg.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.salman.ekg.config.KnowledgeProperties;

@Configuration
@EnableScheduling
public class EmbeddingConfig {

    @Bean
    EmbeddingClient embeddingClient(KnowledgeProperties props) {
        KnowledgeProperties.Embedding e = props.embedding();
        return OllamaEmbeddingClient.create(e.baseUrl(), e.model(), e.timeout());
    }

    /** Periodically embeds anything missed (e.g. uploads made while Ollama was not running). */
    @Component
    @ConditionalOnProperty(name = "ekg.embedding.scheduled-backfill", havingValue = "true", matchIfMissing = true)
    static class BackfillSchedule {

        private final EmbeddingIndexer indexer;

        BackfillSchedule(EmbeddingIndexer indexer) {
            this.indexer = indexer;
        }

        @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT2M")
        void run() {
            indexer.backfill();
        }
    }
}
