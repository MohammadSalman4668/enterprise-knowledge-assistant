package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.Assessment;
import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.embedding.SemanticScorer;
import com.salman.ekg.text.TextAnalysis;

/**
 * Decides, in Java and before any answer is written, whether the evidence is sufficient.
 * A passage supports a sub-question when
 * <ul>
 *   <li><b>by words:</b> it covers at least {@code minCoverage} of the sub-question's content
 *       terms (and at least two of them when there are two or more), or</li>
 *   <li><b>by meaning:</b> its embedding similarity to the sub-question reaches the calibrated
 *       {@code minSimilarity} (so "paid time off" can be supported by an "annual leave" passage).</li>
 * </ul>
 * No support for any sub-question means the agent abstains without calling a model. If the
 * embedding model is unavailable, only the word check applies.
 */
@Component
public class EvidenceGate {

    private static final int MAX_SUPPORT = 3;

    private final double minCoverage;
    private final SemanticScorer semantic;

    public EvidenceGate(KnowledgeProperties props) {
        this(props, SemanticScorer.none());
    }

    @Autowired
    public EvidenceGate(KnowledgeProperties props, SemanticScorer semantic) {
        this.minCoverage = props.agent().minCoverage();
        this.semantic = semantic;
    }

    public List<Assessment> assess(List<SubQuestion> subs, List<EvidenceItem> evidence) {
        List<Assessment> out = new ArrayList<>();
        List<Long> chunkIds = evidence.stream().map(e -> e.chunk().chunkId()).toList();
        for (SubQuestion sub : subs) {
            List<String> terms = sub.keyTerms();
            int minMatched = Math.min(2, terms.size());
            Map<Long, Double> similarity = semantic.similarityToChunks(sub.text(), chunkIds);
            record Scored(EvidenceItem item, double coverage, double similarity, double strength) {}
            List<Scored> supported = new ArrayList<>();
            double bestCoverage = 0;
            double bestSimilarity = 0;
            for (EvidenceItem e : evidence) {
                Set<String> stems = TextAnalysis.stemSet(e.coverageText());
                double cov = TextAnalysis.coverage(terms, stems);
                int matched = TextAnalysis.matchedTerms(terms, stems).size();
                double sim = similarity.getOrDefault(e.chunk().chunkId(), 0.0);
                bestCoverage = Math.max(bestCoverage, cov);
                bestSimilarity = Math.max(bestSimilarity, sim);
                boolean byWords = !terms.isEmpty() && cov >= minCoverage && matched >= minMatched;
                boolean byMeaning = sim >= semantic.minSimilarity();
                if (byWords || byMeaning) {
                    supported.add(new Scored(e, cov, sim, Math.max(cov, sim)));
                }
            }
            supported.sort(Comparator.comparingDouble(Scored::strength).reversed()
                    .thenComparing(s -> -s.item().chunk().score()));
            out.add(new Assessment(sub, !supported.isEmpty(), round(bestCoverage), round(bestSimilarity),
                    supported.stream().limit(MAX_SUPPORT).map(Scored::item).toList()));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
