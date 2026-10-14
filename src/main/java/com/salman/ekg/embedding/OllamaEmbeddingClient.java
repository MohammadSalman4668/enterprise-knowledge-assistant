package com.salman.ekg.embedding;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Calls a local Ollama server's OpenAI-compatible {@code POST /v1/embeddings} endpoint,
 * e.g. with {@code nomic-embed-text} (768 dimensions). Free and offline.
 */
public class OllamaEmbeddingClient implements EmbeddingClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(int index, float[] embedding) {}

    private final Supplier<RestClient> http;
    private final String model;

    public OllamaEmbeddingClient(RestClient http, String model) {
        this(() -> http, model);
    }

    private OllamaEmbeddingClient(Supplier<RestClient> http, String model) {
        this.http = http;
        this.model = model;
    }

    /** The HTTP client is created on first use, so application startup never depends on it. */
    public static OllamaEmbeddingClient create(String baseUrl, String model, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        AtomicReference<RestClient> client = new AtomicReference<>();
        return new OllamaEmbeddingClient(() -> client.updateAndGet(existing -> existing != null ? existing : build(base, timeout)), model);
    }

    private static RestClient build(String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbeddingResponse response = http.get().post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", texts))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()) {
            throw new IllegalStateException("Embedding server returned " + (response == null || response.data() == null
                    ? 0 : response.data().size()) + " vectors for " + texts.size() + " inputs");
        }
        return response.data().stream()
                .sorted(Comparator.comparingInt(Item::index))
                .map(Item::embedding)
                .toList();
    }
}
