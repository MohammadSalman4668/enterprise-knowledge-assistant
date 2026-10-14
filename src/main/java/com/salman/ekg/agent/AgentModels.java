package com.salman.ekg.agent;

import com.salman.ekg.domain.AgentMode;

/**
 * The model-dependent parts of the agent for the current mode. In SIMULATED mode both
 * are the deterministic implementations; in LIVE mode both call the local Ollama model, with the
 * deterministic ones kept as fallbacks by {@link ResearchAgent}.
 */
public record AgentModels(AgentMode mode, String modelName, EvidencePlanner planner, AnswerSynthesizer synthesizer) {
}
