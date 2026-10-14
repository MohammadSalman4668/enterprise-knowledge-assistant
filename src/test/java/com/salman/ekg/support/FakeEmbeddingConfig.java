package com.salman.ekg.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.salman.ekg.embedding.EmbeddingClient;
import com.salman.ekg.text.TextAnalysis;

/**
 * Deterministic stand-in for the Ollama embedding model in tests: a hashed bag of stemmed
 * content words plus a tiny synonym table ("vacation" → annual leave, "home" → remote…).
 * It is not a real language model; it exists so tests exercise the semantic plumbing
 * (indexing, retrieval, fusion, evidence by meaning) quickly and without Ollama.
 */
@TestConfiguration
public class FakeEmbeddingConfig {

    static final int DIMENSIONS = 1024;
    static final Set<String> PREFIX_WORDS = Set.of("search", "query", "document");
    static final Map<String, List<String>> SYNONYMS = Map.of(
            "vacation", List.of("annual", "leave"),
            "pto", List.of("paid", "leave"),
            "home", List.of("remote", "work"),
            "remotely", List.of("remote", "work"),
            "job", List.of("work"),
            "stay", List.of("hotel"),
            "overnight", List.of("hotel", "night"));

    @Bean
    @Primary
    EmbeddingClient fakeEmbeddingClient() {
        return FakeEmbeddingConfig::embedAll;
    }

    public static List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        texts.forEach(t -> out.add(embed(t)));
        return out;
    }

    public static float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        for (String token : TextAnalysis.searchTerms(text)) {
            if (PREFIX_WORDS.contains(token)) {
                continue;
            }
            for (String concept : SYNONYMS.getOrDefault(token, List.of(TextAnalysis.stem(token)))) {
                v[Math.floorMod(concept.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        return v;
    }
}
