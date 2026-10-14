package com.salman.ekg.agent;

import java.util.List;

import com.salman.ekg.agent.AgentTypes.SynthesisInput;

/**
 * Writes the answer body from supported evidence. Every sentence must carry [E#]
 * citations; {@link CitationValidator} enforces this afterwards regardless of who wrote it.
 */
public interface AnswerSynthesizer {

    String compose(SynthesisInput input);

    /**
     * Second chance after the validator rejected {@code rejectedAnswer}: the model is told
     * exactly what was wrong. The default simply composes again (used by the extractive
     * synthesizer, whose output never fails validation).
     */
    default String repair(SynthesisInput input, String rejectedAnswer, List<String> problems) {
        return compose(input);
    }
}
