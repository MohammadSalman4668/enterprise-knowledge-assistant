package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.Assessment;
import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.text.TextAnalysis;

/**
 * SIMULATED synthesizer (no model). Selects the evidence sentences that best cover each
 * supported sub-question and quotes them with their citation. Because it only ever
 * copies evidence sentences, it cannot invent facts. It is also the fallback when the
 * live model times out or its output fails validation.
 */
@Component
public class ExtractiveSynthesizer implements AnswerSynthesizer {

    private static final int MAX_SENTENCES_PER_PART = 2;

    @Override
    public String compose(SynthesisInput input) {
        Set<Integer> conflicted = new HashSet<>();
        input.conflicts().forEach(c -> conflicted.add(c.subQuestionIndex()));
        List<String> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Assessment part : input.supportedParts()) {
            if (conflicted.contains(part.subQuestion().index())) {
                continue; // stated by the deterministic conflict notice instead
            }
            out.addAll(bestSentences(part, used));
        }
        return String.join(" ", out);
    }

    private List<String> bestSentences(Assessment part, Set<String> used) {
        List<String> terms = part.subQuestion().keyTerms();
        record Candidate(String sentence, EvidenceItem item, double coverage, int rank) {}
        List<Candidate> candidates = new ArrayList<>();
        int rank = 0;
        for (EvidenceItem e : part.support()) {
            for (String s : TextAnalysis.sentences(e.text())) {
                if (s.contains(InjectionGuard.REMOVED_MARKER) || s.length() < 12) {
                    continue;
                }
                double cov = TextAnalysis.coverage(terms, TextAnalysis.stemSet(s));
                if (cov > 0) {
                    candidates.add(new Candidate(s, e, cov, rank));
                }
            }
            rank++;
        }
        candidates.sort(Comparator.comparingDouble(Candidate::coverage).reversed()
                .thenComparingInt(Candidate::rank));
        List<String> picked = new ArrayList<>();
        Set<String> coveredTerms = new HashSet<>();
        for (Candidate c : candidates) {
            if (picked.size() >= MAX_SENTENCES_PER_PART || used.contains(c.sentence())) {
                continue;
            }
            List<String> newTerms = TextAnalysis.matchedTerms(terms, TextAnalysis.stemSet(c.sentence())).stream()
                    .filter(t -> !coveredTerms.contains(t)).toList();
            if (!picked.isEmpty() && newTerms.isEmpty() && c.coverage() < 1.0) {
                continue; // a second sentence must add a term, or fully answer the goal on its own
            }
            coveredTerms.addAll(newTerms);
            used.add(c.sentence());
            picked.add(withCitation(c.sentence(), c.item().ref()));
        }
        return picked;
    }

    private static String withCitation(String sentence, String ref) {
        String s = sentence.strip();
        String end = s.endsWith(".") || s.endsWith("!") || s.endsWith("?") ? s.substring(s.length() - 1) : ".";
        String body = s.endsWith(end) ? s.substring(0, s.length() - 1) : s;
        return body + " [" + ref + "]" + end;
    }
}
