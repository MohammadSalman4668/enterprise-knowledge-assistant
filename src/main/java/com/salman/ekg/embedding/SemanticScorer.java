package com.salman.ekg.embedding;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Meaning-based similarity used by the evidence gate and the extractive synthesizer.
 * Every method degrades to "no information" (empty results) instead of failing, so the
 * agent falls back to purely lexical checks when the embedding model is unavailable.
 */
public interface SemanticScorer {

    /** Cosine similarity between {@code text} and each chunk's stored vector; chunks without a vector are absent. */
    Map<Long, Double> similarityToChunks(String text, Collection<Long> chunkIds);

    /** Cosine similarity between {@code text} and each candidate (same order), or an empty list if unavailable. */
    List<Double> similarityToTexts(String text, List<String> candidates);

    /** Similarity at or above which a passage counts as evidence by meaning. */
    double minSimilarity();

    /** Scorer used when semantic search is disabled: never reports a similarity. */
    static SemanticScorer none() {
        return new SemanticScorer() {
            @Override
            public Map<Long, Double> similarityToChunks(String text, Collection<Long> chunkIds) {
                return Map.of();
            }

            @Override
            public List<Double> similarityToTexts(String text, List<String> candidates) {
                return List.of();
            }

            @Override
            public double minSimilarity() {
                return Double.POSITIVE_INFINITY;
            }
        };
    }
}
