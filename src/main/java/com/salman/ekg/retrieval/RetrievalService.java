package com.salman.ekg.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.repository.KnowledgeChunkRepository;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.AccessPolicyService;
import com.salman.ekg.text.TextAnalysis;

/**
 * Hybrid retrieval: runs every configured retriever (keyword full-text and, when enabled,
 * semantic embeddings) <em>in parallel</em> under one time budget, then fuses their rankings.
 *
 * <ul>
 *   <li><b>Fusion:</b> Reciprocal Rank Fusion over three signals: keyword rank, meaning
 *       (semantic) rank, and how fully the passage's own text covers the question's key terms.
 *       A passage that is strong in any signal rises; one strong in several rises most.</li>
 *   <li><b>Graceful degradation:</b> if one retriever times out or fails, the others' results
 *       are used and a warning is returned (e.g. keyword-only while Ollama is down). If every
 *       retriever fails the caller gets a clear 503 instead of a misleading empty result.</li>
 *   <li><b>Defence in depth:</b> whatever a retriever returns is re-checked against the access
 *       policy <em>and</em> the database (current version, not superseded, searchable status).</li>
 * </ul>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int CANDIDATE_FACTOR = 3;
    private static final int MIN_CANDIDATES = 15;
    /** Standard RRF damping constant: rank 1 scores 1/61, rank 10 scores 1/70. */
    static final int RRF_K = 60;
    public static final String HYBRID = "HYBRID";

    public record Result(List<RetrievedChunk> hits, String mode, boolean fallbackUsed, List<String> warnings) {}

    private final List<Retriever> retrievers;
    private final AccessPolicyService accessPolicy;
    private final KnowledgeChunkRepository chunks;
    private final ExecutorService executor;
    private final Duration timeout;

    public RetrievalService(List<Retriever> retrievers, AccessPolicyService accessPolicy,
                            KnowledgeChunkRepository chunks, ExecutorService knowledgeExecutor,
                            KnowledgeProperties props) {
        this.retrievers = List.copyOf(retrievers);
        this.accessPolicy = accessPolicy;
        this.chunks = chunks;
        this.executor = knowledgeExecutor;
        this.timeout = props.retrieval().timeout();
    }

    public Result retrieve(String text, CurrentUser user, String category, AccessLevel accessLevel, int limit) {
        Set<AccessLevel> allowed = accessPolicy.allowedLevels(user.role());
        if (allowed.isEmpty() || (accessLevel != null && !allowed.contains(accessLevel))) {
            // Filtering on a level the caller cannot read yields nothing, not an error that would confirm it exists.
            return new Result(List.of(), "NONE", false, List.of());
        }
        // Over-fetch candidates so fusion can promote the best passage from any signal.
        Retriever.Query query = new Retriever.Query(text, user.role(), category, accessLevel,
                Math.max(limit * CANDIDATE_FACTOR, MIN_CANDIDATES));

        Map<Retriever, CompletableFuture<List<RetrievedChunk>>> running = new LinkedHashMap<>();
        retrievers.forEach(r -> running.put(r, CompletableFuture.supplyAsync(() -> r.retrieve(query), executor)));
        long deadline = System.nanoTime() + timeout.toNanos();

        Map<String, List<RetrievedChunk>> succeeded = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<Retriever, CompletableFuture<List<RetrievedChunk>>> entry : running.entrySet()) {
            String name = entry.getKey().name();
            CompletableFuture<List<RetrievedChunk>> future = entry.getValue();
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                succeeded.put(name, future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                warnings.add(describe(name) + " timed out after " + timeout.toMillis() + " ms; results come from the other search only");
                log.warn("Retriever {} timed out", name);
            } catch (ExecutionException e) {
                warnings.add(describe(name) + " is unavailable (" + e.getCause().getClass().getSimpleName()
                        + "); results come from the other search only");
                log.warn("Retriever {} failed: {}", name, e.getCause().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (succeeded.isEmpty()) {
            throw ApiException.unavailable("RETRIEVAL_UNAVAILABLE",
                    "Knowledge search is temporarily unavailable. Please try again shortly.");
        }
        List<RetrievedChunk> fused = fuse(text, succeeded);
        List<RetrievedChunk> permitted = enforce(fused, allowed);
        String mode = succeeded.size() > 1 ? HYBRID : succeeded.keySet().iterator().next();
        return new Result(permitted.stream().limit(limit).toList(), mode, !warnings.isEmpty(), List.copyOf(warnings));
    }

    private static String describe(String retrieverName) {
        return switch (retrieverName) {
            case RetrievedChunk.SEMANTIC -> "Semantic (meaning) search";
            case RetrievedChunk.KEYWORD -> "Keyword search";
            default -> retrieverName + " retriever";
        };
    }

    /**
     * Reciprocal Rank Fusion of each retriever's ranking plus a lexical-coverage ranking
     * (how completely the passage's own section + content covers the question's key terms;
     * the document title is excluded because every chunk of a document shares it).
     * Ties are broken by coverage, then keyword score, then id, so the order is deterministic.
     */
    static List<RetrievedChunk> fuse(String text, Map<String, List<RetrievedChunk>> rankings) {
        record Acc(RetrievedChunk chunk, double[] rrf, Set<String> sources, double[] keywordScore, double[] similarity) {}
        Map<Long, Acc> acc = new LinkedHashMap<>();
        rankings.forEach((source, list) -> {
            for (int i = 0; i < list.size(); i++) {
                RetrievedChunk h = list.get(i);
                Acc a = acc.computeIfAbsent(h.chunkId(), id -> new Acc(h, new double[1], new LinkedHashSet<>(),
                        new double[] {-1}, new double[] {-1}));
                a.rrf()[0] += 1.0 / (RRF_K + i + 1);
                a.sources().add(source);
                if (RetrievedChunk.SEMANTIC.equals(source)) {
                    a.similarity()[0] = h.similarity();
                } else {
                    a.keywordScore()[0] = Math.max(a.keywordScore()[0], h.score());
                }
            }
        });
        List<String> terms = TextAnalysis.keyTerms(text);
        Map<Long, Double> coverage = new HashMap<>();
        acc.forEach((id, a) -> coverage.put(id, terms.isEmpty() ? 0.0 : TextAnalysis.coverage(terms,
                TextAnalysis.stemSet(innermostHeading(a.chunk().sectionRef()) + " " + a.chunk().content()))));
        List<Long> byCoverage = coverage.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        for (int i = 0; i < byCoverage.size(); i++) {
            acc.get(byCoverage.get(i)).rrf()[0] += 1.0 / (RRF_K + i + 1);
        }
        return acc.values().stream()
                .sorted(Comparator.comparingDouble((Acc a) -> a.rrf()[0]).reversed()
                        .thenComparing(Comparator.comparingDouble((Acc a) -> coverage.get(a.chunk().chunkId())).reversed())
                        .thenComparing(Comparator.comparingDouble((Acc a) -> a.keywordScore()[0]).reversed())
                        .thenComparingLong(a -> a.chunk().chunkId()))
                .map(a -> a.chunk().withMatch(
                        a.keywordScore()[0] >= 0 ? a.keywordScore()[0] : a.similarity()[0],
                        a.similarity()[0],
                        String.join("+", a.sources().stream().sorted().toList())))
                .toList();
    }

    /** "Annual Leave Policy > Entitlement" -> "Entitlement": outer headings usually repeat the title. */
    private static String innermostHeading(String sectionRef) {
        if (sectionRef == null) {
            return "";
        }
        int i = sectionRef.lastIndexOf(" > ");
        return i < 0 ? sectionRef : sectionRef.substring(i + 3);
    }

    private List<RetrievedChunk> enforce(List<RetrievedChunk> hits, Set<AccessLevel> allowed) {
        List<RetrievedChunk> permitted = hits.stream().filter(h -> allowed.contains(h.accessLevel())).toList();
        if (permitted.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        permitted.forEach(h -> ids.add(h.chunkId()));
        Set<Long> live = new HashSet<>(chunks.filterLiveAndAccessible(ids, DocumentStatus.SEARCHABLE, allowed));
        Set<Long> seen = new HashSet<>();
        return permitted.stream()
                .filter(h -> live.contains(h.chunkId()) && seen.add(h.chunkId()))
                .toList();
    }
}
