package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.TraceStep;
import com.salman.ekg.retrieval.RetrievedChunk;
import com.salman.ekg.security.CurrentUser;

/**
 * Per-request state of one research run. The caller's identity is fixed at construction:
 * nothing the model says or the documents contain can change {@link #user()}.
 */
public final class AgentContext {

    private final CurrentUser user;
    private final String category;
    private final int maxToolCalls;
    private int toolCallsUsed;
    private final List<TraceStep> trace = new ArrayList<>();
    /** Chunks returned by search tool calls in this run: the only chunks read_chunk may open. */
    private final Map<Long, RetrievedChunk> searchHits = new LinkedHashMap<>();
    private final Map<Long, EvidenceItem> evidenceByChunk = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private String retrievalMode = "NONE";
    private boolean retrievalFallback;

    public AgentContext(CurrentUser user, String category, int maxToolCalls) {
        this.user = user;
        this.category = category == null || category.isBlank() ? null : category.strip();
        this.maxToolCalls = maxToolCalls;
    }

    public CurrentUser user() { return user; }
    public String category() { return category; }
    public int maxToolCalls() { return maxToolCalls; }
    public int toolCallsUsed() { return toolCallsUsed; }
    public boolean budgetExhausted() { return toolCallsUsed >= maxToolCalls; }
    public String retrievalMode() { return retrievalMode; }
    public boolean retrievalFallback() { return retrievalFallback; }

    void consumeToolCall() {
        toolCallsUsed++;
    }

    public void addTrace(String phase, String tool, Map<String, Object> input, String status, String detail, long ms) {
        trace.add(new TraceStep(trace.size() + 1, phase, tool, input, status, detail, ms));
    }

    public List<TraceStep> trace() {
        return Collections.unmodifiableList(trace);
    }

    void rememberHits(List<RetrievedChunk> hits, String mode, boolean fallback, List<String> retrievalWarnings) {
        hits.forEach(h -> searchHits.putIfAbsent(h.chunkId(), h));
        retrievalMode = mode;
        retrievalFallback |= fallback;
        retrievalWarnings.stream().filter(w -> !warnings.contains(w)).forEach(warnings::add);
    }

    Optional<RetrievedChunk> searchHit(long chunkId) {
        return Optional.ofNullable(searchHits.get(chunkId));
    }

    Optional<EvidenceItem> evidenceFor(long chunkId) {
        return Optional.ofNullable(evidenceByChunk.get(chunkId));
    }

    EvidenceItem addEvidence(RetrievedChunk chunk, String sanitized, List<String> flags) {
        EvidenceItem item = new EvidenceItem("E" + (evidenceByChunk.size() + 1), chunk, sanitized, flags);
        evidenceByChunk.put(chunk.chunkId(), item);
        return item;
    }

    public List<EvidenceItem> evidence() {
        return List.copyOf(evidenceByChunk.values());
    }

    public void warn(String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
