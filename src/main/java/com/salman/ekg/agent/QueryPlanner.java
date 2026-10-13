package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.text.TextAnalysis;

/**
 * Decomposes a question into sub-questions ("goals"). A split on "and", ";" or "?" is
 * kept only if each side has at least two content terms, so "travel and expenses" stays
 * one goal while "How many leave days do I get and how do I reset my VPN token" becomes two.
 */
@Component
public class QueryPlanner {

    private static final Pattern SPLIT = Pattern.compile("(?i)\\s+and\\s+|;\\s*|\\?\\s*");

    public List<SubQuestion> plan(String question, int maxSubQuestions) {
        String q = TextAnalysis.normalizeWhitespace(question);
        List<String> merged = new ArrayList<>();
        for (String piece : SPLIT.split(q)) {
            String p = piece.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (!merged.isEmpty() && TextAnalysis.keyTerms(p).size() < 2) {
                merged.set(merged.size() - 1, merged.getLast() + " and " + p);
            } else {
                merged.add(p);
            }
        }
        if (merged.size() > 1 && TextAnalysis.keyTerms(merged.getFirst()).size() < 2) {
            merged.set(1, merged.getFirst() + " and " + merged.get(1));
            merged.removeFirst();
        }
        while (merged.size() > Math.max(1, maxSubQuestions)) {
            String last = merged.removeLast();
            merged.set(merged.size() - 1, merged.getLast() + " and " + last);
        }
        if (merged.isEmpty()) {
            merged.add(q);
        }
        List<SubQuestion> subs = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            subs.add(new SubQuestion(i, merged.get(i), TextAnalysis.keyTerms(merged.get(i))));
        }
        return subs;
    }
}
