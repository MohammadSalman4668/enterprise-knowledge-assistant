package com.salman.ekg.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.salman.ekg.agent.AgentContext;
import com.salman.ekg.agent.AgentToolbox;
import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.agent.InjectionGuard;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.Role;
import com.salman.ekg.retrieval.RetrievalService;
import com.salman.ekg.retrieval.RetrievedChunk;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.SearchService;

import tools.jackson.databind.json.JsonMapper;

/** The Ollama adapter, tested against a mocked HTTP server: no real model is called. */
class OllamaAdapterTest {

    private static final String BASE = "http://localhost:11434/v1";
    private static final CurrentUser EMPLOYEE = new CurrentUser("employee", Role.EMPLOYEE);
    private static final RetrievedChunk VPN = new RetrievedChunk(21, 2, "VPN Access Runbook", "IT", AccessLevel.INTERNAL,
            1, 1, "VPN Access Runbook > Resetting a VPN Token", null, "To reset your VPN token, open the self-service portal.", 0.4);

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private MockRestServiceServer server;
    private OllamaClient client;
    private AgentToolbox toolbox;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OllamaClient(builder.baseUrl(BASE).build(), "qwen2.5:7b", 256);
        toolbox = new AgentToolbox(retrieval, mock(SearchService.class), new InjectionGuard());
        when(retrieval.retrieve(anyString(), any(), any(), any(), anyInt()))
                .thenReturn(new RetrievalService.Result(List.of(VPN), "KEYWORD", false, List.of()));
    }

    private static String toolCall(String id, String name, String argumentsJson) {
        return """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
                  "tool_calls":[{"id":"%s","type":"function","function":{"name":"%s","arguments":%s}}]}}]}"""
                .formatted(id, name, argumentsJson);
    }

    @Test
    @DisplayName("Planner executes the model's tool calls through the toolbox with the caller's identity")
    void plannerRunsToolLoop() {
        server.expect(requestTo(BASE + "/chat/completions")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"tools\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"model\":\"qwen2.5:7b\"")))
                .andRespond(withSuccess(toolCall("c1", "search_knowledge", "\"{\\\"query\\\":\\\"reset vpn token\\\"}\""),
                        MediaType.APPLICATION_JSON));
        // Some servers send arguments as an object rather than a JSON string: both are accepted.
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"role\":\"tool\"")))
                .andRespond(withSuccess(toolCall("c2", "read_chunk", "{\"chunk_id\":21}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"DONE\"}}]}",
                        MediaType.APPLICATION_JSON));

        AgentContext ctx = new AgentContext(EMPLOYEE, null, 10);
        new OllamaEvidencePlanner(client, toolbox, JsonMapper.builder().build())
                .collect(ctx, "How do I reset my VPN token?", List.of(new SubQuestion(0, "reset vpn token", List.of("reset", "vpn", "token"))));

        server.verify();
        verify(retrieval).retrieve(eq("reset vpn token"), eq(EMPLOYEE), any(), any(), anyInt());
        assertThat(ctx.evidence()).singleElement().satisfies(e -> assertThat(e.chunk().chunkId()).isEqualTo(21L));
        assertThat(ctx.toolCallsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("Synthesizer returns the model's text; empty choices are a clear error (triggers fallback)")
    void synthesizer() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"tools\""))))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Open the portal [E1].\"}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        OllamaSynthesizer synth = new OllamaSynthesizer(client);
        SynthesisInput input = new SynthesisInput("q", List.of(), List.of());
        assertThat(synth.compose(input)).isEqualTo("Open the portal [E1].");
        assertThatThrownBy(() -> synth.compose(input)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Repair continues the conversation with the rejected answer and the validator's reasons")
    void repairSendsRejectedAnswerAndProblems() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("rejected by the citation checker")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UNCITED_SENTENCE_REMOVED: x")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"role\":\"assistant\"")))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"Fixed [E1].\"}}]}", MediaType.APPLICATION_JSON));
        String fixed = new OllamaSynthesizer(client).repair(new SynthesisInput("q", List.of(), List.of()),
                "bad answer", List.of("UNCITED_SENTENCE_REMOVED: x"));
        assertThat(fixed).isEqualTo("Fixed [E1].");
        server.verify();
    }

    @Test
    @DisplayName("Regression: the answer prompt contains no example fact a small model could copy")
    void answerPromptHasNoCopyableExampleFacts() {
        assertThat(AgentPrompts.ANSWER_SYSTEM).doesNotContain("25 days").doesNotContainPattern("\"[A-Z][^\"]*\\[E1]\\.\"");
        assertThat(AgentPrompts.ANSWER_SYSTEM).contains("title");
    }

    @Test
    @DisplayName("Provider resolution: ollama (default) needs a base URL and model; simulated needs nothing; others rejected")
    void providerResolution() {
        Duration t = Duration.ofSeconds(1);
        assertThat(new KnowledgeProperties.Llm("ollama", "qwen2.5:3b", t, 1, BASE).resolve())
                .isEqualTo(KnowledgeProperties.Llm.Provider.OLLAMA);
        assertThat(new KnowledgeProperties.Llm(null, "qwen2.5:3b", t, 1, BASE).resolve())
                .isEqualTo(KnowledgeProperties.Llm.Provider.OLLAMA);
        assertThat(new KnowledgeProperties.Llm("simulated", "", t, 1, "").resolve())
                .isEqualTo(KnowledgeProperties.Llm.Provider.SIMULATED);
        assertThatThrownBy(() -> new KnowledgeProperties.Llm("ollama", "m", t, 1, " ").resolve())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KnowledgeProperties.Llm("unknown-provider", "m", t, 1, BASE).resolve())
                .isInstanceOf(IllegalStateException.class);
    }
}
