package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.ValidationResult;
import com.salman.ekg.text.TextAnalysis;

/**
 * Post-generation check applied to every answer body, whichever synthesizer wrote it.
 * A sentence is kept only if it:
 * <ol>
 *   <li>cites at least one evidence reference that exists in this run;</li>
 *   <li>has most of its content terms present in the cited evidence; and</li>
 *   <li>contains no number that is absent from the cited evidence (a strong signal of a
 *       hallucinated figure).</li>
 * </ol>
 * Everything dropped is reported as an issue in the trace.
 */
@Component
public class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[(E\\d+)]");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern TRAILING_CITATIONS = Pattern.compile("([.!?])\\s*((?:\\[E\\d+]\\s*)+)");
    /** Models write citation lists in several styles: "[E2, E3]", "[E2; E3]", "[e2]". */
    private static final Pattern CITATION_GROUP = Pattern.compile("(?i)\\[\\s*(E\\d+(?:\\s*[,;]\\s*E\\d+)*)\\s*]");
    private static final Pattern INSUFFICIENT = Pattern.compile("(?i)insufficient[_\\s]evidence");
    private static final double MIN_SUPPORT = 0.6;

    public ValidationResult validate(String body, Map<String, EvidenceItem> evidenceByRef) {
        List<String> issues = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<String> kept = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return new ValidationResult("", List.of(), List.of("EMPTY_ANSWER"));
        }
        // "[E2, E3]" / "[e2]" -> "[E2][E3]" / "[E2]"
        String grouped = CITATION_GROUP.matcher(body).replaceAll(m -> Arrays.stream(m.group(1).split("\\s*[,;]\\s*"))
                .map(r -> "[" + r.strip().toUpperCase() + "]").reduce("", String::concat));
        // "...per night. [E1]" -> "...per night [E1]." so citations stay with their sentence
        String normalized = TRAILING_CITATIONS.matcher(grouped).replaceAll(m -> " " + m.group(2).strip() + m.group(1) + " ");
        for (String sentence : TextAnalysis.sentences(normalized)) {
            String plain = CITATION.matcher(sentence).replaceAll("").replaceAll("\\s+([.!?,;:])", "$1").strip();
            if (plain.chars().noneMatch(Character::isLetterOrDigit)) {
                continue;
            }
            if (plain.endsWith("?")) {
                continue; // small models often repeat the question first; a question is not a claim to keep
            }
            if (INSUFFICIENT.matcher(plain).find()) {
                issues.add("MODEL_REPORTED_INSUFFICIENT_EVIDENCE: " + abbreviate(plain));
                continue;
            }
            Set<String> refs = new LinkedHashSet<>();
            Matcher m = CITATION.matcher(sentence);
            while (m.find()) {
                refs.add(m.group(1));
            }
            if (refs.isEmpty()) {
                issues.add("UNCITED_SENTENCE_REMOVED: " + abbreviate(plain));
                continue;
            }
            List<String> valid = refs.stream().filter(evidenceByRef::containsKey).toList();
            refs.stream().filter(r -> !evidenceByRef.containsKey(r))
                    .forEach(r -> issues.add("UNKNOWN_CITATION_REMOVED: " + r));
            if (valid.isEmpty()) {
                issues.add("SENTENCE_WITHOUT_VALID_CITATION_REMOVED: " + abbreviate(plain));
                continue;
            }
            StringBuilder evidenceText = new StringBuilder();
            valid.forEach(r -> evidenceText.append(evidenceByRef.get(r).coverageText()).append(' '));
            Set<String> evidenceStems = TextAnalysis.stemSet(evidenceText.toString());
            List<String> terms = TextAnalysis.keyTerms(plain);
            if (!terms.isEmpty() && TextAnalysis.coverage(terms, evidenceStems) < MIN_SUPPORT) {
                issues.add("UNSUPPORTED_CLAIM_REMOVED: " + abbreviate(plain));
                continue;
            }
            String unsupportedNumber = firstNumberNotIn(plain, evidenceText.toString());
            if (unsupportedNumber != null) {
                issues.add("UNSUPPORTED_NUMBER_REMOVED (" + unsupportedNumber + "): " + abbreviate(plain));
                continue;
            }
            used.addAll(valid);
            String citations = valid.stream().map(r -> "[" + r + "]").reduce((a, b) -> a + b).orElse("");
            kept.add(withCitations(plain, citations));
        }
        return new ValidationResult(String.join(" ", kept), List.copyOf(used), issues);
    }

    private static String firstNumberNotIn(String sentence, String evidence) {
        Set<String> evidenceNumbers = new HashSet<>();
        Matcher e = NUMBER.matcher(evidence);
        while (e.find()) {
            evidenceNumbers.add(e.group().replace(",", ""));
        }
        Matcher s = NUMBER.matcher(sentence);
        while (s.find()) {
            String n = s.group().replace(",", "");
            if (!evidenceNumbers.contains(n)) {
                return n;
            }
        }
        return null;
    }

    private static String withCitations(String plain, String citations) {
        char last = plain.charAt(plain.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return plain.substring(0, plain.length() - 1) + " " + citations + last;
        }
        return plain + " " + citations + ".";
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
