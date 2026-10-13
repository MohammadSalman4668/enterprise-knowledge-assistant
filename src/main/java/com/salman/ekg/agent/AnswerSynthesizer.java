package com.salman.ekg.agent;

import com.salman.ekg.agent.AgentTypes.SynthesisInput;

/**
 * Writes the answer body from supported evidence. Every sentence must carry [E#]
 * citations; {@link CitationValidator} enforces this afterwards regardless of who wrote it.
 */
public interface AnswerSynthesizer {

    String compose(SynthesisInput input);
}
