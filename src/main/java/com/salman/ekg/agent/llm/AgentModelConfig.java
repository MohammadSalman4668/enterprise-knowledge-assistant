package com.salman.ekg.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.salman.ekg.agent.AgentModels;
import com.salman.ekg.agent.AgentToolbox;
import com.salman.ekg.agent.DeterministicEvidencePlanner;
import com.salman.ekg.agent.ExtractiveSynthesizer;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AgentMode;

/**
 * Chooses LIVE (Anthropic) or SIMULATED at startup. With {@code ekg.llm.provider=auto}
 * the app is live only when ANTHROPIC_API_KEY is set; otherwise it runs the clearly
 * labelled simulation. The key is read from the environment and never logged.
 */
@Configuration
public class AgentModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentModelConfig.class);
    public static final String SIMULATED_MODEL_NAME = "simulated-extractive-v1";

    @Bean
    AgentModels agentModels(KnowledgeProperties props, AgentToolbox toolbox,
                            DeterministicEvidencePlanner deterministicPlanner, ExtractiveSynthesizer extractive) {
        KnowledgeProperties.Llm llm = props.llm();
        if (llm.liveRequested()) {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(llm.apiKey())
                    .timeout(llm.timeout())
                    .maxRetries(1)
                    .build();
            log.info("Agent mode LIVE using model {}", llm.model());
            return new AgentModels(AgentMode.LIVE, llm.model(),
                    new ClaudeEvidencePlanner(client, toolbox, llm.model(), llm.maxTokens()),
                    new ClaudeSynthesizer(client, llm.model(), llm.maxTokens()));
        }
        log.warn("Agent mode SIMULATED: no model is called (set ANTHROPIC_API_KEY to enable the live model)");
        return new AgentModels(AgentMode.SIMULATED, SIMULATED_MODEL_NAME, deterministicPlanner, extractive);
    }
}
