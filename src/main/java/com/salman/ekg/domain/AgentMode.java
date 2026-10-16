package com.salman.ekg.domain;

/**
 * Whether answers come from a live AI model or from the clearly labelled deterministic simulation.
 */
public enum AgentMode {
    /** Deterministic planner + extractive synthesizer. No model is called. Clearly labelled in the UI. */
    SIMULATED,
    /** Local Ollama model drives tool selection and writes the answer (still validated in Java). */
    LIVE
}
