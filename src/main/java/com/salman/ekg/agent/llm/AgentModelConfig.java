package com.salman.ekg.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.salman.ekg.agent.AgentModels;
import com.salman.ekg.agent.AgentToolbox;
import com.salman.ekg.agent.DeterministicEvidencePlanner;
import com.salman.ekg.agent.ExtractiveSynthesizer;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AgentMode;

import tools.jackson.databind.ObjectMapper;

/**
 * Chooses the agent's model at startup (see {@link KnowledgeProperties.Llm#resolve()}):
 * a local Ollama model, or the clearly labelled simulation.
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
            case OLLAMA -> {
                OllamaClient client = new OllamaClient(
                        OllamaClient.configure(RestClient.builder(), llm.baseUrl(), llm.timeout()),
                        llm.model(), llm.maxTokens());
                log.info("Agent mode LIVE using Ollama model {} at {}", llm.model(), llm.baseUrl());
                yield new AgentModels(AgentMode.LIVE, llm.model(),
                        new OllamaEvidencePlanner(client, toolbox, mapper),
                        new OllamaSynthesizer(client));
            }
            case SIMULATED -> {
                log.warn("Agent mode SIMULATED: no model is called (set ekg.llm.provider=ollama to use the local model)");
                yield new AgentModels(AgentMode.SIMULATED, SIMULATED_MODEL_NAME, deterministicPlanner, extractive);
            }
        };
    }
}
