package com.salman.ekg.ingestion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.text.TextAnalysis;

/**
 * Structure-aware, token-sized chunker. Sizes are approximate tokens ({@link TokenCounter}).
 * <ul>
 *   <li><b>Boundaries, in order of preference:</b> headings, then paragraphs, then sentences;
 *       a single sentence longer than the maximum is split between words as a last resort.</li>
 *   <li><b>Size:</b> a chunk grows until it reaches {@code minTokens}, never beyond
 *       {@code maxTokens}. A new section starts a new chunk once the current one is big enough;
 *       smaller neighbouring sections are packed together so chunks are not uselessly tiny.</li>
 *   <li><b>Context:</b> heading lines stay in the chunk text where their section starts, and
 *       every chunk records its heading path ("Annual Leave Policy &gt; Carry-over").</li>
 *   <li><b>Overlap:</b> when one section continues into the next chunk, that chunk starts with
 *       about {@code overlapTokens} of the previous chunk's closing sentences. Overlap never
 *       crosses a section or a PDF page, so whole sections are not duplicated.</li>
 *   <li><b>Pages:</b> a chunk never spans two PDF pages, so it has one page citation.</li>
 *   <li><b>Deterministic:</b> the same text and settings always give the same chunks.</li>
 * </ul>
 */
@Component
public class Chunker {

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    /** Plain-text headings: short ALL-CAPS lines without a trailing full stop. */
    private static final Pattern CAPS_HEADING = Pattern.compile("^[A-Z][A-Z0-9 &/,'()\\-]{2,79}$");
    private static final Pattern WORD = Pattern.compile("\\S+");

    public record ChunkDraft(int index, String sectionRef, Integer pageNumber, String content) {}

    /** A piece of one section: an optional heading block followed by text, with its size. */
    private record Unit(List<String> section, String heading, String text, int tokens) {}

    private final TokenCounter tokens;
    private final int minTokens;
    private final int maxTokens;
    private final int overlapTokens;

    public Chunker(KnowledgeProperties props) {
        KnowledgeProperties.Ingestion cfg = props.ingestion();
        this.tokens = TokenCounter.of(cfg.chunkTokenizer());
        this.maxTokens = Math.max(50, cfg.chunkMaxTokens());
        this.minTokens = Math.clamp(cfg.chunkMinTokens(), 1, maxTokens);
        this.overlapTokens = Math.clamp(cfg.chunkOverlapTokens(), 0, maxTokens / 2);
    }

    /** Identifies this configuration; stored on every chunk so a rebuild can skip up-to-date documents. */
    public String signature() {
        return tokens.name() + ":" + minTokens + "-" + maxTokens + "/" + overlapTokens;
    }

    public int countTokens(String text) {
        return tokens.count(text);
    }

    public List<ChunkDraft> chunk(ParsedDocument doc) {
        List<ChunkDraft> out = new ArrayList<>();
        Deque<String[]> headingStack = new ArrayDeque<>(); // {level, text}
        for (ParsedDocument.Page page : doc.pages()) {
            pack(units(page.text(), headingStack), page.pageNumber(), out);
        }
        return out;
    }

    // ------------------------------------------------------------------ parsing into units

    /** Splits a page into units. The heading stack carries over between pages (a section can continue). */
    private List<Unit> units(String text, Deque<String[]> headingStack) {
        List<Unit> units = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder pendingHeadings = new StringBuilder();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            Matcher md = MD_HEADING.matcher(line);
            boolean capsHeading = !md.matches() && CAPS_HEADING.matcher(line).matches()
                    && line.chars().anyMatch(Character::isLetter);
            if (md.matches() || capsHeading) {
                addParagraph(units, paragraph, pendingHeadings, headingStack);
                int level = md.matches() ? md.group(1).length() : 1;
                String heading = md.matches() ? md.group(2).strip() : titleCase(line);
                while (!headingStack.isEmpty() && Integer.parseInt(headingStack.peekLast()[0]) >= level) {
                    headingStack.removeLast();
                }
                headingStack.addLast(new String[] {String.valueOf(level), heading});
                if (!pendingHeadings.isEmpty()) {
                    pendingHeadings.append('\n');
                }
                pendingHeadings.append(line);
            } else if (line.isEmpty()) {
                addParagraph(units, paragraph, pendingHeadings, headingStack);
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
            }
        }
        addParagraph(units, paragraph, pendingHeadings, headingStack);
        return units;
    }

    private void addParagraph(List<Unit> units, StringBuilder paragraph, StringBuilder pendingHeadings,
                              Deque<String[]> headingStack) {
        String para = paragraph.toString().strip();
        paragraph.setLength(0);
        if (para.isEmpty()) {
            return; // headings without text yet stay pending and attach to the next paragraph
        }
        List<String> section = headingStack.stream().map(h -> h[1]).toList();
        String heading = pendingHeadings.isEmpty() ? null : pendingHeadings.toString();
        pendingHeadings.setLength(0);
        int headingTokens = heading == null ? 0 : tokens.count(heading);
        if (headingTokens + tokens.count(para) <= maxTokens) {
            units.add(new Unit(section, heading, para, headingTokens + tokens.count(para)));
            return;
        }
        // Over-long paragraph: sentence pieces, packed up to the maximum.
        boolean first = true;
        for (String piece : splitLong(para, Math.max(maxTokens - headingTokens, maxTokens / 2))) {
            String h = first ? heading : null;
            units.add(new Unit(section, h, piece, (h == null ? 0 : headingTokens) + tokens.count(piece)));
            first = false;
        }
    }

    /** Sentences packed up to {@code limit} tokens; a sentence longer than that is split between words. */
    private List<String> splitLong(String para, int limit) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : TextAnalysis.sentences(para)) {
            if (tokens.count(sentence) > limit) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(splitWords(sentence, limit));
                continue;
            }
            String candidate = current.isEmpty() ? sentence : current + " " + sentence;
            if (!current.isEmpty() && tokens.count(candidate) > limit) {
                pieces.add(current.toString());
                current.setLength(0);
                current.append(sentence);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private List<String> splitWords(String sentence, int limit) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Matcher m = WORD.matcher(sentence);
        while (m.find()) {
            String candidate = current.isEmpty() ? m.group() : current + " " + m.group();
            if (!current.isEmpty() && tokens.count(candidate) > limit) {
                pieces.add(current.toString());
                current.setLength(0);
                current.append(m.group());
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    // ------------------------------------------------------------------ packing units into chunks

    private void pack(List<Unit> units, Integer page, List<ChunkDraft> out) {
        List<Unit> current = new ArrayList<>();
        int size = 0;
        for (Unit u : units) {
            if (current.isEmpty()) {
                current.add(u);
                size = u.tokens();
                continue;
            }
            Unit last = current.getLast();
            boolean newSection = !Objects.equals(u.section(), last.section()) || u.heading() != null;
            if (newSection) {
                if (size >= minTokens || size + u.tokens() > maxTokens) {
                    emit(current, page, out);
                    current = new ArrayList<>(List.of(u));
                    size = u.tokens();
                } else {
                    current.add(u);
                    size += u.tokens();
                }
            } else if (size + u.tokens() <= maxTokens) {
                current.add(u);
                size += u.tokens();
            } else {
                // Same section continues in a new chunk: carry a little context over.
                emit(current, page, out);
                Unit overlap = overlap(last, maxTokens - u.tokens());
                current = new ArrayList<>();
                size = 0;
                if (overlap != null) {
                    current.add(overlap);
                    size = overlap.tokens();
                }
                current.add(u);
                size += u.tokens();
            }
        }
        emit(current, page, out);
    }

    /** The closing sentences of {@code previous}, about {@code overlapTokens} long, as a heading-less unit. */
    private Unit overlap(Unit previous, int room) {
        int budget = Math.min(overlapTokens, room);
        if (budget <= 0) {
            return null;
        }
        List<String> sentences = TextAnalysis.sentences(previous.text());
        Deque<String> tail = new ArrayDeque<>();
        int used = 0;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            int n = tokens.count(sentences.get(i));
            if (used + n > budget) {
                break;
            }
            tail.addFirst(sentences.get(i));
            used += n;
        }
        if (tail.isEmpty()) {
            // The last sentence alone is longer than the overlap: take its final words instead.
            List<String> words = new ArrayList<>();
            Matcher m = WORD.matcher(previous.text());
            while (m.find()) {
                words.add(m.group());
            }
            StringBuilder sb = new StringBuilder();
            for (int i = words.size() - 1; i >= 0; i--) {
                String candidate = sb.isEmpty() ? words.get(i) : words.get(i) + " " + sb;
                if (tokens.count("…" + candidate) > budget) {
                    break;
                }
                sb.setLength(0);
                sb.append(candidate);
            }
            if (sb.isEmpty()) {
                return null;
            }
            String text = "…" + sb;
            return new Unit(previous.section(), null, text, tokens.count(text));
        }
        String text = String.join(" ", tail);
        if (text.equals(previous.text())) {
            return null; // would repeat the whole unit, not add context
        }
        return new Unit(previous.section(), null, text, used);
    }

    private void emit(List<Unit> units, Integer page, List<ChunkDraft> out) {
        if (units.isEmpty()) {
            return;
        }
        String content = units.stream()
                .map(u -> u.heading() == null ? u.text() : u.heading() + "\n\n" + u.text())
                .collect(Collectors.joining("\n\n"))
                .strip();
        if (units.stream().allMatch(u -> u.text().chars().noneMatch(Character::isLetterOrDigit))) {
            return; // never index headings over punctuation-only text (e.g. a lone "---")
        }
        out.add(new ChunkDraft(out.size(), sectionRef(units), page, content));
    }

    /** Heading path of the first section; further packed sections add their innermost heading. */
    private static String sectionRef(List<Unit> units) {
        LinkedHashSet<List<String>> sections = new LinkedHashSet<>();
        units.forEach(u -> sections.add(u.section()));
        List<List<String>> paths = sections.stream().filter(p -> !p.isEmpty()).toList();
        if (paths.isEmpty()) {
            return null;
        }
        StringBuilder ref = new StringBuilder(String.join(" > ", paths.getFirst()));
        for (int i = 1; i < paths.size(); i++) {
            ref.append("; ").append(paths.get(i).getLast());
        }
        return ref.length() > 200 ? ref.substring(0, 199) + "…" : ref.toString();
    }

    private static String titleCase(String caps) {
        StringBuilder sb = new StringBuilder();
        for (String word : caps.toLowerCase().split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
            }
        }
        return sb.toString().strip();
    }
}
