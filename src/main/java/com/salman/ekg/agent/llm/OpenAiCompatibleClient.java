package com.salman.ekg.agent.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal client for the OpenAI-compatible {@code /chat/completions} endpoint, which is
 * served by Ollama (local, free), and by several hosted providers. Only the fields this
 * application needs are modelled.
 */
public class OpenAiCompatibleClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message, String finish_reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content, List<ToolCall> tool_calls) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(String id, String type, FunctionCall function) {}

    /** {@code arguments} is a JSON string in the OpenAI format; some servers send an object instead. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(String name, Object arguments) {}

    private final RestClient http;
    private final String model;
    private final int maxTokens;

    public OpenAiCompatibleClient(RestClient http, String model, int maxTokens) {
        this.http = http;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public String model() {
        return model;
    }

    /** Sends one chat turn; {@code tools} may be empty. Returns the first choice's message. */
    public Message chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        ChatResponse response = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new IllegalStateException("Model server returned no choices");
        }
        return response.choices().getFirst().message();
    }

    /** Configures base URL, optional Bearer key and timeouts on a (Spring-provided) RestClient builder. */
    public static RestClient configure(RestClient.Builder builder, String baseUrl, String apiKey, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(timeout);
        builder.baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        return builder.build();
    }
}
