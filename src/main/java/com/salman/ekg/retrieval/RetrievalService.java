package com.salman.ekg.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * Runs the retriever chain with a per-retriever timeout and falls back to the next one
 * on timeout or error. If every retriever fails the caller gets a clear 503
 * (RETRIEVAL_UNAVAILABLE) instead of an empty result that would look like "no evidence".
 *
 * <p>Defence in depth: whatever a retriever returns is re-checked against the access
 * policy <em>and</em> against the database (current version, not superseded, searchable
 * status) before anything leaves this class.</p>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int CANDIDATE_FACTOR = 3;
    private static final int MIN_CANDIDATES = 15;

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
        // Over-fetch candidates so the coverage re-rank below can promote the best passage.
        Retriever.Query query = new Retriever.Query(text, user.role(), category, accessLevel,
                Math.max(limit * CANDIDATE_FACTOR, MIN_CANDIDATES));
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < retrievers.size(); i++) {
            Retriever retriever = retrievers.get(i);
            CompletableFuture<List<RetrievedChunk>> future =
                    CompletableFuture.supplyAsync(() -> retriever.retrieve(query), executor);
            try {
                List<RetrievedChunk> hits = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                List<RetrievedChunk> ranked = rerank(text, enforce(hits, allowed));
                return new Result(ranked.stream().limit(limit).toList(), retriever.name(), i > 0, List.copyOf(warnings));
            } catch (TimeoutException e) {
                future.cancel(true);
                warnings.add(retriever.name() + " retriever timed out after " + timeout.toMillis() + " ms");
                log.warn("Retriever {} timed out", retriever.name());
            } catch (ExecutionException e) {
                warnings.add(retriever.name() + " retriever failed (" + e.getCause().getClass().getSimpleName() + ")");
                log.warn("Retriever {} failed", retriever.name(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw ApiException.unavailable("RETRIEVAL_UNAVAILABLE",
                "Knowledge search is temporarily unavailable. Please try again shortly.");
    }

    /**
     * Orders candidates by how completely the passage's own section + content covers the
     * question's key terms (the document title is shared by every chunk of a document, so
     * it is excluded here), then by the retriever's score. This promotes the passage that
     * actually answers the question over one that merely repeats a frequent term.
     */
    static List<RetrievedChunk> rerank(String text, List<RetrievedChunk> hits) {
        List<String> terms = TextAnalysis.keyTerms(text);
        if (terms.isEmpty() || hits.size() < 2) {
            return hits;
        }
        Map<Long, Double> coverage = new HashMap<>();
        hits.forEach(h -> coverage.put(h.chunkId(), TextAnalysis.coverage(terms,
                TextAnalysis.stemSet(innermostHeading(h.sectionRef()) + " " + h.content()))));
        return hits.stream()
                .sorted(Comparator.comparingDouble((RetrievedChunk h) -> coverage.get(h.chunkId())).reversed()
                        .thenComparing(Comparator.comparingDouble(RetrievedChunk::score).reversed()))
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
