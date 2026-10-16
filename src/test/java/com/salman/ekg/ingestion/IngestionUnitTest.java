package com.salman.ekg.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.salman.ekg.exception.ApiException;
import com.salman.ekg.support.TestProps;

class IngestionUnitTest {

    private final DocumentParser parser = new DocumentParser();
    private final Chunker chunker = new Chunker(TestProps.defaults());

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            return e.getCode();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.docx", "script.sh", "image.png", "noextension", "trailingdot."})
    @DisplayName("TC-05-02 (unit): unsupported extensions are rejected")
    void rejectsUnsupportedTypes(String name) {
        assertThat(code(() -> parser.parse(name, utf8("hello")))).isEqualTo("UNSUPPORTED_FILE_TYPE");
    }

    @Test
    @DisplayName("TC-05-02 (unit): empty, whitespace-only, binary and invalid UTF-8 inputs are rejected")
    void rejectsEmptyAndBinary() {
        assertThat(code(() -> parser.parse("a.txt", new byte[0]))).isEqualTo("EMPTY_DOCUMENT");
        assertThat(code(() -> parser.parse("a.md", utf8(" \n\t \n")))).isEqualTo("EMPTY_DOCUMENT");
        assertThat(code(() -> parser.parse("a.txt", new byte[] {'a', 0, 'b'}))).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(code(() -> parser.parse("a.txt", new byte[] {(byte) 0xC3, (byte) 0x28}))).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(code(() -> parser.parse("a.pdf", utf8("plain text pretending")))).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(code(() -> parser.parse("a.pdf", utf8("%PDF-1.7 truncated garbage")))).isEqualTo("UNREADABLE_DOCUMENT");
    }

    @Test
    @DisplayName("Supported text formats parse; extension check is case-insensitive and BOM is stripped")
    void parsesText() {
        ParsedDocument md = parser.parse("Guide.MD", utf8("﻿# Title\n\nBody"));
        assertThat(md.contentType()).isEqualTo("text/markdown");
        assertThat(md.fullText()).startsWith("# Title");
        assertThat(parser.parse("a.txt", utf8("x")).contentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("PDF text is extracted per page so chunks carry page numbers")
    void parsesPdfWithPages() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : List.of("First page about leave.", "Second page about VPN tokens.")) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ParsedDocument parsed = parser.parse("guide.pdf", pdf);
        assertThat(parsed.pages()).hasSize(2);
        List<Chunker.ChunkDraft> chunks = chunker.chunk(parsed);
        assertThat(chunks).extracting(Chunker.ChunkDraft::pageNumber).containsExactly(1, 2);
        assertThat(chunks.get(1).content()).contains("VPN tokens");
    }

    @Test
    @DisplayName("Small neighbouring sections are packed into one chunk that keeps every heading and the heading path")
    void chunkerKeepsSections() {
        String md = """
                # Leave Policy
                Intro text.

                ## Entitlement
                You get 25 days.

                ## Carry-over
                Up to 5 days.
                """;
        List<Chunker.ChunkDraft> chunks = chunker.chunk(parser.parse("p.md", utf8(md)));
        // Each section is far below the 350-token minimum: one chunk, with headings kept in the text.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().sectionRef()).isEqualTo("Leave Policy; Entitlement; Carry-over");
        assertThat(chunks.getFirst().content()).isEqualTo("""
                # Leave Policy

                Intro text.

                ## Entitlement

                You get 25 days.

                ## Carry-over

                Up to 5 days.""");
    }

    @Test
    @DisplayName("Plain-text ALL-CAPS lines are headings; long paragraphs split on sentence boundaries within the 400-token maximum")
    void chunkerSplitsLongText() {
        String sentence = "This sentence about the expense policy is fairly long and repeats. ";
        String txt = "EXPENSES\n\n" + sentence.repeat(100); // about 1,200 tokens
        List<Chunker.ChunkDraft> chunks = chunker.chunk(parser.parse("e.txt", utf8(txt)));
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(chunker.countTokens(c.content())).isLessThanOrEqualTo(400);
            assertThat(c.sectionRef()).isEqualTo("Expenses");
            assertThat(c.content()).endsWith(".");
        });
    }

    @Test
    void unreadablePdfIsRejectedNotIndexed() {
        assertThatThrownBy(() -> parser.parse("x.pdf", utf8("%PDF-")))
                .isInstanceOf(ApiException.class);
    }
}
