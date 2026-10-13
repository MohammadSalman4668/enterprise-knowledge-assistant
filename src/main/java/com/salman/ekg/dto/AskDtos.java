package com.salman.ekg.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.salman.ekg.agent.AgentTypes.TraceStep;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AgentMode;
import com.salman.ekg.domain.AnswerOutcome;

public final class AskDtos {

    private AskDtos() {
    }

    public record AskRequest(
            @NotBlank @Size(max = 1000) String question,
            @Size(max = 64) String category) {
    }

    public record Citation(String ref, long chunkId, long documentId, String title, int version, String location,
                           String sectionRef, Integer pageNumber, int chunkIndex) {
    }

    /** Everything the agent read, kept for inspection. {@code text} is the sanitised passage. */
    public record Evidence(String ref, long chunkId, long documentId, String title, String category,
                           AccessLevel accessLevel, int version, String location, double score, String text,
                           List<String> injectionFlags, boolean cited) {
    }

    public record SubQuestionResult(int index, String text, List<String> keyTerms, boolean supported,
                                    double bestCoverage, List<String> supportingRefs) {
    }

    public record ConflictFact(String ref, String documentTitle, String value, String sentence) {
    }

    public record ConflictResult(String quantity, List<ConflictFact> facts) {
    }

    public record AskResponse(
            Long answerId,
            String question,
            String answer,
            AnswerOutcome outcome,
            AgentMode mode,
            String model,
            boolean degraded,
            String retrievalMode,
            List<Citation> citations,
            List<Evidence> evidence,
            List<SubQuestionResult> subQuestions,
            List<ConflictResult> conflicts,
            List<String> validationIssues,
            List<String> warnings,
            List<TraceStep> trace,
            int toolCalls,
            long latencyMs,
            Instant createdAt) {

        public AskResponse withAnswerId(Long id) {
            return new AskResponse(id, question, answer, outcome, mode, model, degraded, retrievalMode, citations,
                    evidence, subQuestions, conflicts, validationIssues, warnings, trace, toolCalls, latencyMs,
                    createdAt);
        }
    }

    public record AgentStatus(AgentMode mode, String model, boolean simulated, List<String> retrievers,
                              int maxToolCalls, double minCoverage, String note) {
    }
}
