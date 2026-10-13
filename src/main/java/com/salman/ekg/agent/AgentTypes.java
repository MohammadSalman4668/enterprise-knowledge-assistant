package com.salman.ekg.agent;

import java.util.List;
import java.util.Map;

import com.salman.ekg.retrieval.RetrievedChunk;

/** Value types shared by the agent workflow. */
public final class AgentTypes {

    private AgentTypes() {
    }

    /** One goal derived from the user's question. Compound questions produce several. */
    public record SubQuestion(int index, String text, List<String> keyTerms) {}

    /**
     * A passage the agent actually read, under a stable reference label (E1, E2…).
     * {@code text} is the sanitised content; the original never reaches a model.
     */
    public record EvidenceItem(String ref, RetrievedChunk chunk, String text, List<String> injectionFlags) {

        public boolean flagged() {
            return !injectionFlags.isEmpty();
        }

        /** Text used to judge coverage: title + section + sanitised content. */
        public String coverageText() {
            return chunk.title() + " " + (chunk.sectionRef() == null ? "" : chunk.sectionRef()) + " " + text;
        }
    }

    /** One entry in the inspectable run trace. */
    public record TraceStep(int step, String phase, String tool, Map<String, Object> input, String status,
                            String detail, long durationMs) {}

    public record ToolResult(boolean ok, String content) {

        public static ToolResult ok(String content) {
            return new ToolResult(true, content);
        }

        public static ToolResult error(String content) {
            return new ToolResult(false, content);
        }
    }

    /** Declarative tool description (also sent to a live model as its tool schema). */
    public record ToolSpec(String name, String description, Map<String, Map<String, Object>> properties,
                           List<String> required) {}

    /** Gate verdict for one sub-question. */
    public record Assessment(SubQuestion subQuestion, boolean supported, double bestCoverage,
                             List<EvidenceItem> support) {}

    public record ConflictingFact(String ref, String documentTitle, String value, String sentence) {}

    public record Conflict(int subQuestionIndex, String quantity, List<ConflictingFact> facts) {}

    public record SynthesisInput(String question, List<Assessment> supportedParts, List<Conflict> conflicts) {}

    public record ValidationResult(String text, List<String> usedRefs, List<String> issues) {}
}
