package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.Assessment;
import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.text.TextAnalysis;

/**
 * Decides, in Java and before any answer is written, whether the evidence is sufficient.
 * A passage supports a sub-question when it covers at least {@code minCoverage} of the
 * sub-question's content terms (and at least two of them when there are two or more).
 * No support for any sub-question means the agent abstains without calling a model.
 */
@Component
public class EvidenceGate {

    private static final int MAX_SUPPORT = 3;

    private final double minCoverage;

    public EvidenceGate(KnowledgeProperties props) {
        this.minCoverage = props.agent().minCoverage();
    }

    public List<Assessment> assess(List<SubQuestion> subs, List<EvidenceItem> evidence) {
        List<Assessment> out = new ArrayList<>();
        for (SubQuestion sub : subs) {
            List<String> terms = sub.keyTerms();
            int minMatched = Math.min(2, terms.size());
            record Scored(EvidenceItem item, double coverage) {}
            List<Scored> scored = new ArrayList<>();
            for (EvidenceItem e : evidence) {
                Set<String> stems = TextAnalysis.stemSet(e.coverageText());
                double cov = TextAnalysis.coverage(terms, stems);
                int matched = TextAnalysis.matchedTerms(terms, stems).size();
                if (!terms.isEmpty() && cov >= minCoverage && matched >= minMatched) {
                    scored.add(new Scored(e, cov));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::coverage).reversed()
                    .thenComparing(s -> -s.item().chunk().score()));
            double best = scored.isEmpty() ? bestCoverage(terms, evidence) : scored.getFirst().coverage();
            out.add(new Assessment(sub, !scored.isEmpty(), round(best),
                    scored.stream().limit(MAX_SUPPORT).map(Scored::item).toList()));
        }
        return out;
    }

    private static double bestCoverage(List<String> terms, List<EvidenceItem> evidence) {
        return evidence.stream()
                .mapToDouble(e -> TextAnalysis.coverage(terms, TextAnalysis.stemSet(e.coverageText())))
                .max().orElse(0.0);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
