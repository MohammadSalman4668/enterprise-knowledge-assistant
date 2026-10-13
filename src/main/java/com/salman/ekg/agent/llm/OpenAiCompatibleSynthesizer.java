package com.salman.ekg.agent.llm;

import java.util.List;
import java.util.Map;

import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.agent.AnswerSynthesizer;

/**
 * LIVE synthesizer for OpenAI-compatible servers. Uses the same system prompt and
 * evidence formatting as {@link ClaudeSynthesizer}; output is validated by
 * {@code CitationValidator} exactly as for every other synthesizer.
 */
public class OpenAiCompatibleSynthesizer implements AnswerSynthesizer {

    private final OpenAiCompatibleClient client;

    public OpenAiCompatibleSynthesizer(OpenAiCompatibleClient client) {
        this.client = client;
    }

    @Override
    public String compose(SynthesisInput input) {
        OpenAiCompatibleClient.Message reply = client.chat(List.of(
                Map.of("role", "system", "content", ClaudeSynthesizer.SYSTEM),
                Map.of("role", "user", "content", ClaudeSynthesizer.prompt(input))), List.of());
        return reply.content() == null ? "" : reply.content();
    }
}
