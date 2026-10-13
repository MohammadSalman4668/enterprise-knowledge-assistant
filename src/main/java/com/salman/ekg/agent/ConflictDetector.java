package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.Assessment;
import com.salman.ekg.agent.AgentTypes.Conflict;
import com.salman.ekg.agent.AgentTypes.ConflictingFact;
import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.text.TextAnalysis;

/**
 * Detects when two <em>different</em> documents give different values for the same kind
 * of quantity in sentences relevant to the same sub-question (e.g. "$200 per night" vs
 * "$180 per night"). The answer then states the conflict and cites both sources instead
 * of silently picking one.
 */
@Component
public class ConflictDetector {

    private static final Pattern MONEY = Pattern.compile("\\$\\s?(\\d[\\d,]*(?:\\.\\d+)?)(?:\\s+(?:per|a|each)\\s+(\\w+))?");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(working days?|business days?|days?|hours?|minutes?|weeks?|months?|years?|percent|%)");
    private static final double RELEVANT_SENTENCE = 0.5;

    private record Fact(String quantity, String value, String sentence) {}

    public List<Conflict> detect(List<Assessment> assessments) {
        List<Conflict> conflicts = new ArrayList<>();
        for (Assessment a : assessments) {
            if (!a.supported()) {
                continue;
            }
            // quantity -> (evidence -> facts)
            Map<String, Map<EvidenceItem, List<Fact>>> byQuantity = new LinkedHashMap<>();
            Set<Long> seenDocs = new LinkedHashSet<>();
            for (EvidenceItem e : a.support()) {
                if (!seenDocs.add(e.chunk().documentId())) {
                    continue; // one passage per document: conflicts are between documents
                }
                for (Fact f : facts(e.text(), a.subQuestion().keyTerms())) {
                    byQuantity.computeIfAbsent(f.quantity(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(e, k -> new ArrayList<>()).add(f);
                }
            }
            for (Map.Entry<String, Map<EvidenceItem, List<Fact>>> entry : byQuantity.entrySet()) {
                Map<EvidenceItem, List<Fact>> perDoc = entry.getValue();
                if (perDoc.size() < 2) {
                    continue;
                }
                List<Set<String>> valueSets = perDoc.values().stream()
                        .map(fs -> (Set<String>) new LinkedHashSet<>(fs.stream().map(Fact::value).toList()))
                        .toList();
                boolean disjoint = true;
                for (int i = 0; i < valueSets.size() && disjoint; i++) {
                    for (int j = i + 1; j < valueSets.size(); j++) {
                        if (!Collections.disjoint(valueSets.get(i), valueSets.get(j))) {
                            disjoint = false;
                            break;
                        }
                    }
                }
                if (disjoint) {
                    List<ConflictingFact> facts = perDoc.entrySet().stream()
                            .map(en -> new ConflictingFact(en.getKey().ref(), en.getKey().chunk().title(),
                                    en.getValue().getFirst().value(), en.getValue().getFirst().sentence()))
                            .toList();
                    conflicts.add(new Conflict(a.subQuestion().index(), entry.getKey(), facts));
                }
            }
        }
        return conflicts;
    }

    private List<Fact> facts(String text, List<String> terms) {
        List<Fact> out = new ArrayList<>();
        for (String sentence : TextAnalysis.sentences(text)) {
            if (TextAnalysis.coverage(terms, TextAnalysis.stemSet(sentence)) < RELEVANT_SENTENCE) {
                continue;
            }
            Matcher money = MONEY.matcher(sentence);
            while (money.find()) {
                String per = money.group(2) == null ? "" : " per " + TextAnalysis.stem(money.group(2));
                out.add(new Fact("amount" + per, "$" + money.group(1).replace(",", ""), sentence));
            }
            Matcher qty = QUANTITY.matcher(sentence);
            while (qty.find()) {
                String unit = normalizeUnit(qty.group(2));
                out.add(new Fact(unit, qty.group(1) + " " + unit, sentence));
            }
        }
        return out;
    }

    private static String normalizeUnit(String unit) {
        String u = unit.toLowerCase(Locale.ROOT).strip();
        if (u.equals("%")) {
            return "percent";
        }
        return u.endsWith("s") ? u.substring(0, u.length() - 1) : u;
    }
}
