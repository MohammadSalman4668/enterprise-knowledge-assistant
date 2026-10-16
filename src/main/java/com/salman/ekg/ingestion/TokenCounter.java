package com.salman.ekg.ingestion;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, dependency-free token counting used to size chunks. These are
 * <em>approximations</em> of a model tokenizer, documented so chunk sizes are reproducible:
 * <ul>
 *   <li>{@code approximate} (default): every run of letters, every run of digits and every
 *       punctuation/symbol character is one piece; a letter run longer than 8 characters counts
 *       as two. For English prose this lands within roughly 10-15% of BPE tokenizers such as the
 *       one used by bge-m3.</li>
 *   <li>{@code whitespace}: one token per whitespace-separated word (undercounts punctuation).</li>
 * </ul>
 */
public final class TokenCounter {

    private static final Pattern PIECE = Pattern.compile("\\p{L}+|\\p{N}+|[^\\s\\p{L}\\p{N}]");
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final int LONG_WORD = 8;

    private final boolean whitespace;
    private final String name;

    private TokenCounter(boolean whitespace, String name) {
        this.whitespace = whitespace;
        this.name = name;
    }

    public static TokenCounter of(String name) {
        String n = name == null || name.isBlank() ? "approximate" : name.strip().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "approximate" -> new TokenCounter(false, n);
            case "whitespace" -> new TokenCounter(true, n);
            default -> throw new IllegalStateException(
                    "Unknown ekg.ingestion.chunk-tokenizer '" + name + "'. Use 'approximate' or 'whitespace'");
        };
    }

    public String name() {
        return name;
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher m = (whitespace ? WORD : PIECE).matcher(text);
        int n = 0;
        while (m.find()) {
            String piece = m.group();
            n += !whitespace && piece.length() > LONG_WORD && Character.isLetter(piece.charAt(0)) ? 2 : 1;
        }
        return n;
    }
}
