package com.salman.ekg.ingestion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.text.TextAnalysis;

/**
 * Structure-aware chunker.
 * <ul>
 *   <li>A chunk never spans two sections or two PDF pages, so every chunk has one precise
 *       citation reference (section path and/or page).</li>
 *   <li>Paragraphs are packed up to {@code chunkSize} characters; an over-long paragraph is
 *       split on sentence boundaries (and hard-split only if a single sentence is too long).</li>
 *   <li>Section references are heading paths, e.g. "Annual Leave &gt; Carry-over".</li>
 * </ul>
 */
@Component
public class Chunker {

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    /** Plain-text headings: short ALL-CAPS lines without a trailing full stop. */
    private static final Pattern CAPS_HEADING = Pattern.compile("^[A-Z][A-Z0-9 &/,'()\\-]{2,79}$");

    public record ChunkDraft(int index, String sectionRef, Integer pageNumber, String content) {}

    private final int chunkSize;

    public Chunker(KnowledgeProperties props) {
        this.chunkSize = Math.max(200, props.ingestion().chunkSize());
    }

    public List<ChunkDraft> chunk(ParsedDocument doc) {
        List<ChunkDraft> out = new ArrayList<>();
        Deque<String[]> headingStack = new ArrayDeque<>(); // {level, text}
        for (ParsedDocument.Page page : doc.pages()) {
            StringBuilder paragraph = new StringBuilder();
            StringBuilder buffer = new StringBuilder();
            for (String rawLine : page.text().split("\\R")) {
                String line = rawLine.stripTrailing();
                Matcher md = MD_HEADING.matcher(line.strip());
                boolean capsHeading = !md.matches() && CAPS_HEADING.matcher(line.strip()).matches()
                        && line.strip().chars().anyMatch(Character::isLetter);
                if (md.matches() || capsHeading) {
                    addParagraph(buffer, paragraph, out, sectionRef(headingStack), page.pageNumber());
                    flush(buffer, out, sectionRef(headingStack), page.pageNumber());
                    int level = md.matches() ? md.group(1).length() : 1;
                    String text = md.matches() ? md.group(2).strip() : titleCase(line.strip());
                    while (!headingStack.isEmpty() && Integer.parseInt(headingStack.peekLast()[0]) >= level) {
                        headingStack.removeLast();
                    }
                    headingStack.addLast(new String[] {String.valueOf(level), text});
                } else if (line.isBlank()) {
                    addParagraph(buffer, paragraph, out, sectionRef(headingStack), page.pageNumber());
                } else {
                    if (!paragraph.isEmpty()) {
                        paragraph.append('\n');
                    }
                    paragraph.append(line.strip());
                }
            }
            addParagraph(buffer, paragraph, out, sectionRef(headingStack), page.pageNumber());
            flush(buffer, out, sectionRef(headingStack), page.pageNumber());
        }
        return out;
    }

    private void addParagraph(StringBuilder buffer, StringBuilder paragraph, List<ChunkDraft> out,
                              String section, Integer page) {
        if (paragraph.isEmpty()) {
            return;
        }
        String para = paragraph.toString().strip();
        paragraph.setLength(0);
        if (para.isEmpty()) {
            return;
        }
        if (para.length() > chunkSize) {
            flush(buffer, out, section, page);
            for (String piece : splitLong(para)) {
                buffer.append(piece);
                flush(buffer, out, section, page);
            }
            return;
        }
        if (!buffer.isEmpty() && buffer.length() + 2 + para.length() > chunkSize) {
            flush(buffer, out, section, page);
        }
        if (!buffer.isEmpty()) {
            buffer.append("\n\n");
        }
        buffer.append(para);
    }

    private List<String> splitLong(String para) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : TextAnalysis.sentences(para)) {
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += chunkSize) {
                    pieces.add(sentence.substring(i, Math.min(sentence.length(), i + chunkSize)));
                }
                continue;
            }
            if (!current.isEmpty() && current.length() + 1 + sentence.length() > chunkSize) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private void flush(StringBuilder buffer, List<ChunkDraft> out, String section, Integer page) {
        String content = buffer.toString().strip();
        buffer.setLength(0);
        if (!content.isEmpty()) {
            out.add(new ChunkDraft(out.size(), section, page, content));
        }
    }

    private static String sectionRef(Deque<String[]> stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String ref = stack.stream().map(h -> h[1]).collect(Collectors.joining(" > "));
        return ref.length() > 200 ? ref.substring(0, 200) : ref;
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
