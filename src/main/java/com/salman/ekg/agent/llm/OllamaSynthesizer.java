package com.salman.ekg.agent.llm;

import java.util.List;
import java.util.Map;

import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.agent.AnswerSynthesizer;

/**
 * LIVE synthesizer: the local Ollama model writes the answer from the gated evidence only
 * (see {@link AgentPrompts}). The output is still validated by {@code CitationValidator};
 * a bad answer gets one repair attempt, then is replaced by the extractive fallback.
 */
public class OllamaSynthesizer implements AnswerSynthesizer {

    private final OllamaClient client;

    public OllamaSynthesizer(OllamaClient client) {
        this.client = client;
    }

    @Override
    public String compose(SynthesisInput input) {
        return text(client.chat(List.of(
                Map.of("role", "system", "content", AgentPrompts.ANSWER_SYSTEM),
                Map.of("role", "user", "content", AgentPrompts.answerPrompt(input))), List.of()));
    }

    /** Continues the same conversation: the rejected answer, then the validator's specific complaints. */
    @Override
    public String repair(SynthesisInput input, String rejectedAnswer, List<String> problems) {
        return text(client.chat(List.of(
                Map.of("role", "system", "content", AgentPrompts.ANSWER_SYSTEM),
                Map.of("role", "user", "content", AgentPrompts.answerPrompt(input)),
                Map.of("role", "assistant", "content", rejectedAnswer == null ? "" : rejectedAnswer),
                Map.of("role", "user", "content", AgentPrompts.repairRequest(problems))), List.of()));
    }

    private static String text(OllamaClient.Message reply) {
        return reply.content() == null ? "" : reply.content();
    }
}
