package com.salman.ekg.embedding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.salman.ekg.config.KnowledgeProperties;

/**
 * Embeds queries and documents with the configured model, applying the model's task
 * prefixes and L2-normalising every vector (so cosine similarity is a dot product).
 * Query vectors are cached briefly: the agent embeds the same sub-question for search and
 * again for the evidence check.
 */
@Service
public class EmbeddingService implements SemanticScorer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int QUERY_CACHE_SIZE = 256;

    private final EmbeddingClient client;
    private final ChunkEmbeddingRepository vectors;
    private final KnowledgeProperties.Embedding config;
    private final Map<String, float[]> queryCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > QUERY_CACHE_SIZE;
        }
    };

    public EmbeddingService(EmbeddingClient client, ChunkEmbeddingRepository vectors, KnowledgeProperties props) {
        this.client = client;
        this.vectors = vectors;
        this.config = props.embedding();
    }

    public boolean enabled() {
        return config != null && config.enabled();
    }

    public String model() {
        return config.model();
    }

    /** Embeds a search query (cached). Throws if the model is unavailable. */
    public float[] embedQuery(String text) {
        String key = text.strip();
        synchronized (queryCache) {
            float[] cached = queryCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        float[] v = normalize(client.embed(List.of(prefixed(config.queryPrefix(), key))).getFirst());
        synchronized (queryCache) {
            queryCache.put(key, v);
        }
        return v;
    }

    /** Embeds document passages (title + section + text). Throws if the model is unavailable. */
    public List<float[]> embedDocuments(List<String> texts) {
        List<String> inputs = texts.stream().map(t -> prefixed(config.documentPrefix(), t)).toList();
        return client.embed(inputs).stream().map(EmbeddingService::normalize).toList();
    }

    @Override
    public Map<Long, Double> similarityToChunks(String text, Collection<Long> chunkIds) {
        if (!enabled() || chunkIds.isEmpty()) {
            return Map.of();
        }
        try {
            float[] q = embedQuery(text);
            Map<Long, Double> out = new LinkedHashMap<>();
            vectors.findByChunkIds(chunkIds, config.model()).forEach((id, v) -> out.put(id, dot(q, v)));
            return out;
        } catch (RuntimeException e) {
            log.warn("Semantic similarity unavailable ({}); using keyword checks only", e.toString());
            return Map.of();
        }
    }

    @Override
    public List<Double> similarityToTexts(String text, List<String> candidates) {
        if (!enabled() || candidates.isEmpty()) {
            return List.of();
        }
        try {
            float[] q = embedQuery(text);
            List<Double> out = new ArrayList<>();
            for (float[] v : embedDocuments(candidates)) {
                out.add(dot(q, v));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Semantic similarity unavailable ({}); using keyword checks only", e.toString());
            return List.of();
        }
    }

    @Override
    public double minSimilarity() {
        return enabled() ? config.minSimilarity() : Double.POSITIVE_INFINITY;
    }

    private static String prefixed(String prefix, String text) {
        return prefix == null || prefix.isBlank() ? text : prefix.strip() + " " + text;
    }

    public static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v.clone();
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    /** Dot product of two normalised vectors = cosine similarity. Mismatched lengths (different models) score 0. */
    public static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
