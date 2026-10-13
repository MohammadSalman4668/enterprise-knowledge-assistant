package com.salman.ekg.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.salman.ekg.agent.AgentModels;
import com.salman.ekg.agent.AgentToolbox;
import com.salman.ekg.agent.DeterministicEvidencePlanner;
import com.salman.ekg.agent.ExtractiveSynthesizer;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AgentMode;

import tools.jackson.databind.ObjectMapper;

/**
 * Chooses the model provider at startup (see {@link KnowledgeProperties.Llm#resolve()}):
 * the labelled simulation, Anthropic, or any OpenAI-compatible server such as a local,
 * free Ollama. Keys are read from configuration and never logged.
 */
@Configuration
public class AgentModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentModelConfig.class);
    public static final String SIMULATED_MODEL_NAME = "simulated-extractive-v1";

    @Bean
    AgentModels agentModels(KnowledgeProperties props, AgentToolbox toolbox, DeterministicEvidencePlanner deterministicPlanner,
                            ExtractiveSynthesizer extractive, ObjectMapper mapper) {
        KnowledgeProperties.Llm llm = props.llm();
        return switch (llm.resolve()) {
            case ANTHROPIC -> {
                AnthropicClient client = AnthropicOkHttpClient.builder()
                        .apiKey(llm.apiKey())
                        .timeout(llm.timeout())
                        .maxRetries(1)
                        .build();
                log.info("Agent mode LIVE using Anthropic model {}", llm.model());
                yield new AgentModels(AgentMode.LIVE, llm.model(),
                        new ClaudeEvidencePlanner(client, toolbox, llm.model(), llm.maxTokens()),
                        new ClaudeSynthesizer(client, llm.model(), llm.maxTokens()));
            }
            case OPENAI_COMPATIBLE -> {
                OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                        OpenAiCompatibleClient.configure(RestClient.builder(), llm.baseUrl(), llm.apiKey(), llm.timeout()),
                        llm.model(), llm.maxTokens());
                log.info("Agent mode LIVE using OpenAI-compatible model {} at {}", llm.model(), llm.baseUrl());
                yield new AgentModels(AgentMode.LIVE, llm.model(),
                        new OpenAiCompatibleEvidencePlanner(client, toolbox, mapper),
                        new OpenAiCompatibleSynthesizer(client));
            }
            case SIMULATED -> {
                log.warn("Agent mode SIMULATED: no model is called (configure ekg.llm.* to enable a live model)");
                yield new AgentModels(AgentMode.SIMULATED, SIMULATED_MODEL_NAME, deterministicPlanner, extractive);
            }
        };
    }
}
