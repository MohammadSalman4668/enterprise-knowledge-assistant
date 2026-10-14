package com.salman.ekg.agent.llm;

import java.util.List;
import java.util.Map;

import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.agent.AnswerSynthesizer;

/**
 * LIVE synthesizer: the local Ollama model writes the answer from the gated evidence only
 * (see {@link AgentPrompts}). The output is still validated by {@code CitationValidator};
 * a bad answer is dropped and replaced by the extractive fallback, not shown.
 */
public class OllamaSynthesizer implements AnswerSynthesizer {

    private final OllamaClient client;

    public OllamaSynthesizer(OllamaClient client) {
        this.client = client;
    }

    @Override
    public String compose(SynthesisInput input) {
        OllamaClient.Message reply = client.chat(List.of(
                Map.of("role", "system", "content", AgentPrompts.ANSWER_SYSTEM),
                Map.of("role", "user", "content", AgentPrompts.answerPrompt(input))), List.of());
        return reply.content() == null ? "" : reply.content();
    }
}
