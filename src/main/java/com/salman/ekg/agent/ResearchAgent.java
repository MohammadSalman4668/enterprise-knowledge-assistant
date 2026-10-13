package com.salman.ekg.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.salman.ekg.agent.AgentTypes.Assessment;
import com.salman.ekg.agent.AgentTypes.Conflict;
import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.agent.AgentTypes.ValidationResult;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AgentMode;
import com.salman.ekg.domain.AnswerOutcome;
import com.salman.ekg.dto.AskDtos.AskRequest;
import com.salman.ekg.dto.AskDtos.AskResponse;
import com.salman.ekg.dto.AskDtos.Citation;
import com.salman.ekg.dto.AskDtos.ConflictFact;
import com.salman.ekg.dto.AskDtos.ConflictResult;
import com.salman.ekg.dto.AskDtos.Evidence;
import com.salman.ekg.dto.AskDtos.SubQuestionResult;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.security.CurrentUser;

/**
 * Goal-driven research workflow behind {@code POST /api/knowledge/ask}:
 * <pre>
 *  PLAN       split the question into sub-questions (goals)
 *  RESEARCH   planner issues controlled tool calls (search_knowledge, read_chunk)
 *  GATE       Java decides per goal whether evidence is sufficient → abstain if none is
 *  CONFLICT   Java compares figures across documents
 *  SYNTHESIZE model (LIVE) or extractive quoting (SIMULATED) writes a cited answer
 *  VALIDATE   Java drops uncited / unsupported sentences and invented numbers
 *  RECORD     run, citations and full trace are persisted for inspection and feedback
 * </pre>
 * Business rules (permissions, sufficiency, conflicts, citations) are enforced in Java;
 * the model is only trusted to choose searches and to phrase the answer.
 */
@Service
public class ResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgent.class);

    public static final String ABSTAIN_MESSAGE = "I could not find enough evidence in the documents available to you "
            + "to answer this question, so I will not guess. Try rephrasing it, or contact the owner of the relevant policy.";

    private final QueryPlanner queryPlanner;
    private final AgentModels models;
    private final DeterministicEvidencePlanner fallbackPlanner;
    private final ExtractiveSynthesizer fallbackSynthesizer;
    private final EvidenceGate gate;
    private final ConflictDetector conflictDetector;
    private final CitationValidator validator;
    private final AgentRunService runs;
    private final ExecutorService executor;
    private final KnowledgeProperties props;
    private final Clock clock;

    public ResearchAgent(QueryPlanner queryPlanner, AgentModels models, DeterministicEvidencePlanner fallbackPlanner,
                         ExtractiveSynthesizer fallbackSynthesizer, EvidenceGate gate, ConflictDetector conflictDetector,
                         CitationValidator validator, AgentRunService runs, ExecutorService knowledgeExecutor,
                         KnowledgeProperties props, Clock clock) {
        this.queryPlanner = queryPlanner;
        this.models = models;
        this.fallbackPlanner = fallbackPlanner;
        this.fallbackSynthesizer = fallbackSynthesizer;
        this.gate = gate;
        this.conflictDetector = conflictDetector;
        this.validator = validator;
        this.runs = runs;
        this.executor = knowledgeExecutor;
        this.props = props;
        this.clock = clock;
    }

    public AgentModels models() {
        return models;
    }

    public AskResponse ask(AskRequest request, CurrentUser user) {
        long start = System.nanoTime();
        AgentContext ctx = new AgentContext(user, request.category(), props.agent().maxToolCalls());
        boolean degraded = false;

        // PLAN
        List<SubQuestion> subs = queryPlanner.plan(request.question(), props.agent().maxSubQuestions());
        ctx.addTrace("PLAN", null, Map.of("question", request.question()), "OK",
                subs.stream().map(s -> "#" + (s.index() + 1) + " " + s.text() + " " + s.keyTerms())
                        .collect(Collectors.joining(" | ")), 0);

        // RESEARCH
        try {
            models.planner().collect(ctx, request.question(), subs);
        } catch (ApiException e) {
            throw e; // e.g. RETRIEVAL_UNAVAILABLE -> clear 503
        } catch (RuntimeException e) {
            if (models.planner() == fallbackPlanner) {
                throw e;
            }
            degraded = true;
            log.warn("Live research failed, falling back to deterministic research", e);
            ctx.warn("The AI model was unavailable during research (" + e.getClass().getSimpleName()
                    + "); deterministic keyword research was used instead.");
            ctx.addTrace("FALLBACK", null, Map.of(), "DEGRADED", "planner -> deterministic", 0);
            fallbackPlanner.collect(ctx, request.question(), subs);
        }

        // GATE
        List<Assessment> assessments = gate.assess(subs, ctx.evidence());
        if (models.mode() == AgentMode.LIVE) {
            // Safety net: a live model that stops early must not cause a false abstention.
            boolean added = false;
            for (Assessment a : assessments) {
                if (!a.supported() && !ctx.budgetExhausted()) {
                    int before = ctx.evidence().size();
                    fallbackPlanner.collectFor(ctx, a.subQuestion());
                    added |= ctx.evidence().size() > before;
                }
            }
            if (added) {
                assessments = gate.assess(subs, ctx.evidence());
            }
        }
        ctx.addTrace("GATE", null, Map.of("minCoverage", props.agent().minCoverage()), "OK",
                assessments.stream().map(a -> "#" + (a.subQuestion().index() + 1) + (a.supported() ? " supported" : " NOT supported")
                        + " (coverage " + a.bestCoverage() + ")").collect(Collectors.joining(" | ")), 0);

        List<Assessment> supported = assessments.stream().filter(Assessment::supported).toList();
        if (supported.isEmpty()) {
            return finish(request, user, ctx, AnswerOutcome.ABSTAINED, ABSTAIN_MESSAGE, assessments, List.of(),
                    List.of(), Set.of(), degraded, start);
        }

        // CONFLICT CHECK
        List<Conflict> conflicts = conflictDetector.detect(assessments);
        ctx.addTrace("CONFLICT_CHECK", null, Map.of(), conflicts.isEmpty() ? "OK" : "CONFLICT",
                conflicts.isEmpty() ? "No conflicting figures" : conflicts.stream()
                        .map(c -> c.quantity() + ": " + c.facts().stream().map(f -> f.value() + " [" + f.ref() + "]")
                                .collect(Collectors.joining(" vs "))).collect(Collectors.joining("; ")), 0);

        // SYNTHESIZE
        SynthesisInput input = new SynthesisInput(request.question(), supported, conflicts);
        Map<String, EvidenceItem> byRef = new LinkedHashMap<>();
        ctx.evidence().forEach(e -> byRef.put(e.ref(), e));
        String body;
        long synthStart = System.nanoTime();
        try {
            body = withTimeout(() -> models.synthesizer().compose(input), synthesisTimeout());
            ctx.addTrace("SYNTHESIZE", null, Map.of("synthesizer", models.modelName()), "OK",
                    abbreviate(body), ms(synthStart));
        } catch (RuntimeException e) {
            if (models.synthesizer() == fallbackSynthesizer) {
                throw e;
            }
            degraded = true;
            log.warn("Synthesis failed, falling back to extractive answer: {}", e.toString());
            ctx.warn("The AI model did not respond in time (" + e.getClass().getSimpleName()
                    + "); an extractive answer quoting the evidence is shown instead.");
            ctx.addTrace("SYNTHESIZE", null, Map.of("synthesizer", models.modelName()), "DEGRADED",
                    "fallback to extractive synthesizer", ms(synthStart));
            body = fallbackSynthesizer.compose(input);
        }

        // VALIDATE
        // An empty body is legitimate when every supported part is a conflict (stated below by Java).
        ValidationResult validation = body == null || body.isBlank()
                ? new ValidationResult("", List.of(), List.of())
                : validator.validate(body, byRef);
        List<String> issues = new ArrayList<>(validation.issues());
        boolean needsBody = supported.stream().anyMatch(a -> conflicts.stream()
                .noneMatch(c -> c.subQuestionIndex() == a.subQuestion().index()));
        if (validation.text().isBlank() && needsBody && models.synthesizer() != fallbackSynthesizer) {
            degraded = true;
            ctx.warn("The model's answer failed citation validation; an extractive answer from the evidence is shown instead.");
            ValidationResult retry = validator.validate(fallbackSynthesizer.compose(input), byRef);
            issues.addAll(retry.issues());
            validation = new ValidationResult(retry.text(), retry.usedRefs(), issues);
        }
        ctx.addTrace("VALIDATE", null, Map.of(), issues.isEmpty() ? "OK" : "CORRECTED",
                issues.isEmpty() ? "All sentences cited and supported" : String.join(" | ", issues), 0);

        // COMPOSE (deterministic notices are written by Java, not the model)
        StringBuilder answer = new StringBuilder(validation.text());
        Set<String> citedRefs = new LinkedHashSet<>(validation.usedRefs());
        for (Conflict c : conflicts) {
            append(answer, "The sources conflict on the " + c.quantity() + ": " + c.facts().stream()
                    .map(f -> f.documentTitle() + " states " + f.value() + " [" + f.ref() + "]")
                    .collect(Collectors.joining(", while ")) + ". Please confirm with the document owner which value is current.");
            c.facts().forEach(f -> citedRefs.add(f.ref()));
        }
        List<Assessment> unsupported = assessments.stream().filter(a -> !a.supported()).toList();
        for (Assessment a : unsupported) {
            append(answer, "I could not find sufficient evidence in the documents available to you for: \""
                    + a.subQuestion().text() + "\".");
        }
        AnswerOutcome outcome;
        if (answer.toString().isBlank() || citedRefs.isEmpty()) {
            outcome = AnswerOutcome.ABSTAINED;
            answer = new StringBuilder(ABSTAIN_MESSAGE);
            citedRefs.clear();
        } else if (!conflicts.isEmpty()) {
            outcome = AnswerOutcome.CONFLICT;
        } else if (!unsupported.isEmpty()) {
            outcome = AnswerOutcome.PARTIAL;
        } else {
            outcome = AnswerOutcome.ANSWERED;
        }
        return finish(request, user, ctx, outcome, answer.toString(), assessments, conflicts, issues, citedRefs,
                degraded, start);
    }

    private AskResponse finish(AskRequest request, CurrentUser user, AgentContext ctx, AnswerOutcome outcome,
                               String answer, List<Assessment> assessments, List<Conflict> conflicts,
                               List<String> issues, Set<String> citedRefs, boolean degraded, long start) {
        List<EvidenceItem> evidence = ctx.evidence();
        List<Citation> citations = evidence.stream()
                .filter(e -> citedRefs.contains(e.ref()))
                .map(e -> new Citation(e.ref(), e.chunk().chunkId(), e.chunk().documentId(), e.chunk().title(),
                        e.chunk().version(), e.chunk().locationLabel(), e.chunk().sectionRef(), e.chunk().pageNumber(),
                        e.chunk().chunkIndex()))
                .toList();
        List<Evidence> evidenceDtos = evidence.stream()
                .map(e -> new Evidence(e.ref(), e.chunk().chunkId(), e.chunk().documentId(), e.chunk().title(),
                        e.chunk().category(), e.chunk().accessLevel(), e.chunk().version(), e.chunk().locationLabel(),
                        Math.round(e.chunk().score() * 1000.0) / 1000.0, e.text(), e.injectionFlags(),
                        citedRefs.contains(e.ref())))
                .toList();
        if (evidence.stream().anyMatch(EvidenceItem::flagged)) {
            ctx.warn("Instruction-like text was found in retrieved content and neutralised; it was treated as data only.");
        }
        List<SubQuestionResult> subResults = assessments.stream()
                .map(a -> new SubQuestionResult(a.subQuestion().index(), a.subQuestion().text(), a.subQuestion().keyTerms(),
                        a.supported(), a.bestCoverage(), a.support().stream().map(EvidenceItem::ref).toList()))
                .toList();
        List<ConflictResult> conflictDtos = conflicts.stream()
                .map(c -> new ConflictResult(c.quantity(), c.facts().stream()
                        .map(f -> new ConflictFact(f.ref(), f.documentTitle(), f.value(), f.sentence())).toList()))
                .toList();
        ctx.addTrace("RECORD", null, Map.of(), "OK", outcome + " with " + citations.size() + " citation(s)", 0);
        AskResponse response = new AskResponse(null, request.question(), answer, outcome, models.mode(),
                models.modelName(), degraded, ctx.retrievalMode(), citations, evidenceDtos, subResults, conflictDtos,
                issues, ctx.warnings(), ctx.trace(), ctx.toolCallsUsed(), ms(start), clock.instant());
        Long id = runs.save(user, response);
        return response.withAnswerId(id);
    }

    private <T> T withTimeout(Supplier<T> call, Duration timeout) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call, executor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelTimeoutException("Model call exceeded " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelTimeoutException("Interrupted while waiting for the model");
        }
    }

    private Duration synthesisTimeout() {
        return props.llm().timeout().plusSeconds(2);
    }

    private static void append(StringBuilder sb, String sentence) {
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(sentence);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Raised when a model call exceeds its time budget. */
    public static class ModelTimeoutException extends RuntimeException {
        public ModelTimeoutException(String message) {
            super(message);
        }
    }
}
