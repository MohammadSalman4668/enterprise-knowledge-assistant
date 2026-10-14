package com.salman.ekg.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.SearchLog;
import com.salman.ekg.dto.SearchDtos.SearchHit;
import com.salman.ekg.dto.SearchDtos.SearchRequest;
import com.salman.ekg.dto.SearchDtos.SearchResponse;
import com.salman.ekg.repository.SearchLogRepository;
import com.salman.ekg.retrieval.RetrievalService;
import com.salman.ekg.retrieval.RetrievedChunk;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.text.TextAnalysis;

@Service
public class SearchService {

    private static final int SNIPPET_MAX = 260;

    private final RetrievalService retrieval;
    private final SearchLogRepository searchLogs;
    private final KnowledgeProperties props;
    private final Clock clock;

    public SearchService(RetrievalService retrieval, SearchLogRepository searchLogs, KnowledgeProperties props,
                         Clock clock) {
        this.retrieval = retrieval;
        this.searchLogs = searchLogs;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public SearchResponse search(SearchRequest req, CurrentUser user) {
        int limit = req.limit() == null ? props.retrieval().defaultLimit() : req.limit();
        RetrievalService.Result result = retrieval.retrieve(req.query(), user, req.category(), req.accessLevel(), limit);
        List<String> terms = TextAnalysis.keyTerms(req.query());
        List<SearchHit> hits = result.hits().stream().map(h -> toHit(h, terms)).toList();
        record(user, req.query(), req.category(), result, SearchLog.Source.SEARCH);
        return new SearchResponse(req.query(), result.mode(), result.fallbackUsed(), result.warnings(), hits.size(), hits);
    }

    /** Logs a search for analytics. Also used by the agent's search tool (source = AGENT). */
    @Transactional
    public void record(CurrentUser user, String query, String categoryFilter, RetrievalService.Result result,
                       SearchLog.Source source) {
        String filter = categoryFilter == null || categoryFilter.isBlank() ? null : categoryFilter.strip();
        String resolved = filter != null ? filter
                : result.hits().isEmpty() ? null : result.hits().getFirst().category();
        String q = query.length() > 1000 ? query.substring(0, 1000) : query;
        searchLogs.save(new SearchLog(user.username(), user.role(), q, filter, resolved, result.hits().size(),
                result.mode(), source, clock.instant()));
    }

    static SearchHit toHit(RetrievedChunk h, List<String> terms) {
        Set<String> stems = TextAnalysis.stemSet(h.title() + " " + (h.sectionRef() == null ? "" : h.sectionRef())
                + " " + h.content());
        return new SearchHit(h.chunkId(), h.documentId(), h.title(), h.category(), h.accessLevel(), h.version(),
                h.chunkIndex(), h.sectionRef(), h.pageNumber(), h.locationLabel(), snippet(h.content(), terms),
                Math.round(h.score() * 1000.0) / 1000.0, TextAnalysis.matchedTerms(terms, stems), h.matchedBy(),
                h.hasSimilarity() ? Math.round(h.similarity() * 1000.0) / 1000.0 : null);
    }

    /** The sentence that best covers the query terms, so the result list shows why it matched. */
    static String snippet(String content, List<String> terms) {
        String best = TextAnalysis.sentences(content).stream()
                .max(Comparator.comparingDouble(s -> TextAnalysis.coverage(terms, TextAnalysis.stemSet(s))))
                .orElse(content);
        return best.length() > SNIPPET_MAX ? best.substring(0, SNIPPET_MAX) + "…" : best;
    }
}
