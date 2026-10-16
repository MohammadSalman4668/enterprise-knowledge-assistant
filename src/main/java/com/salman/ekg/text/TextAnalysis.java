package com.salman.ekg.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free text utilities shared by chunking, retrieval and the agent.
 * The stemmer is intentionally light (plural / -ing / -ed stripping): it only needs to
 * make "days" match "day" when measuring evidence coverage, not be linguistically perfect.
 */
public final class TextAnalysis {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MD_HEADING_LINE = Pattern.compile("^#{1,6}\\s.*");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\p{N}\"'(\\[])");

    /** Function words and question words that carry no retrieval signal. */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "of", "to", "in", "on", "at", "by",
            "for", "with", "from", "into", "about", "as", "is", "are", "was", "were", "be", "been", "being",
            "am", "do", "does", "did", "done", "doing", "have", "has", "had", "having", "can", "could",
            "should", "would", "will", "shall", "may", "might", "must", "i", "me", "my", "mine", "we", "us",
            "our", "you", "your", "he", "she", "it", "its", "they", "them", "their", "this", "that", "these",
            "those", "there", "here", "what", "whats", "which", "who", "whom", "whose", "when", "where", "why",
            "how", "many", "much", "any", "some", "all", "each", "every", "no", "not", "so", "than", "too",
            "very", "just", "also", "please", "tell", "know", "get", "gets", "got", "need", "needs", "want",
            "s", "t", "up", "out", "over", "under", "again", "per", "via", "vs", "etc");

    /**
     * Domain words that appear in almost every document title ("Leave Policy", "VPN Guide").
     * They are ignored when measuring coverage so that "policy" alone never counts as evidence.
     */
    private static final Set<String> GENERIC = Set.of(
            "policy", "policies", "document", "documents", "company", "guide", "guideline", "guidelines",
            "faq", "faqs", "procedure", "procedures", "rule", "rules", "information", "info", "employee",
            "employees", "staff", "question", "questions", "answer");

    private TextAnalysis() {
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    public static String stem(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.length() <= 3 || t.chars().allMatch(Character::isDigit)) {
            return t;
        }
        if (t.endsWith("ies") && t.length() > 4) {
            t = t.substring(0, t.length() - 3) + "y";
        } else if (t.endsWith("sses") || t.endsWith("ches") || t.endsWith("shes") || t.endsWith("xes")) {
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us") && !t.endsWith("is")) {
            t = t.substring(0, t.length() - 1);
        }
        boolean suffixRemoved = false;
        if (t.endsWith("ly") && t.length() - 2 >= 4) {
            t = t.substring(0, t.length() - 2); // "consecutively" -> "consecutive", "monthly" -> "month"
        } else if (t.endsWith("ment") && t.length() - 4 >= 4) {
            t = t.substring(0, t.length() - 4); // "deployment" -> "deploy", "payment" -> "pay"
        } else if (t.endsWith("ing") && t.length() - 3 >= 3) {
            t = t.substring(0, t.length() - 3);
            suffixRemoved = true;
        } else if (t.endsWith("ed") && t.length() - 2 >= 3) {
            t = t.substring(0, t.length() - 2);
            suffixRemoved = true;
        }
        // "capped" -> "capp" -> "cap", "resetting" -> "resett" -> "reset"
        int n = t.length();
        if (suffixRemoved && n >= 3 && t.charAt(n - 1) == t.charAt(n - 2)
                && "lsz".indexOf(t.charAt(n - 1)) < 0 && "aeiou".indexOf(t.charAt(n - 1)) < 0) {
            t = t.substring(0, n - 1);
        }
        return t;
    }

    public static boolean isStopword(String token) {
        return STOPWORDS.contains(token);
    }

    /** Words worth sending to the keyword index (stopwords removed, original form kept). */
    public static List<String> searchTerms(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : tokens(text)) {
            if (!STOPWORDS.contains(t) && (t.length() >= 2 || Character.isDigit(t.charAt(0)))) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    /** Stemmed content terms used to measure whether a passage actually covers a question. */
    public static List<String> keyTerms(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : searchTerms(text)) {
            if (!GENERIC.contains(t)) {
                out.add(stem(t));
            }
        }
        return new ArrayList<>(out);
    }

    public static Set<String> stemSet(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tokens(text)) {
            out.add(stem(t));
        }
        return out;
    }

    /** Fraction of {@code terms} present in {@code stems}; 0 when there are no terms. */
    public static double coverage(List<String> terms, Set<String> stems) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        long hit = terms.stream().filter(stems::contains).count();
        return (double) hit / terms.size();
    }

    public static List<String> matchedTerms(List<String> terms, Set<String> stems) {
        return terms.stream().filter(stems::contains).toList();
    }

    /** Splits into sentences; line breaks (bullets, table rows) always end a sentence. */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || MD_HEADING_LINE.matcher(trimmed).matches()) {
                continue; // chunks keep "## Heading" lines for context; they are labels, not sentences
            }
            for (String s : SENTENCE_BREAK.split(trimmed)) {
                String clean = s.strip().replaceFirst("^[-*•]\\s+", "");
                if (!clean.isEmpty()) {
                    out.add(clean);
                }
            }
        }
        return out;
    }

    /** Collapses whitespace; used before hashing so formatting-only changes are not new content. */
    public static String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
