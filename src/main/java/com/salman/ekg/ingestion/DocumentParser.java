package com.salman.ekg.ingestion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.salman.ekg.exception.ApiException;

/**
 * Validates and extracts text from an upload. Business rules enforced here:
 * <ul>
 *   <li>Only .txt, .md and .pdf are supported, and the bytes must match the extension
 *       (a renamed binary is rejected, not silently indexed as garbage).</li>
 *   <li>Zero-byte files and files whose extracted text is blank are rejected as empty.</li>
 * </ul>
 */
@Component
public class DocumentParser {

    static final Map<String, String> SUPPORTED = Map.of(
            "txt", "text/plain",
            "md", "text/markdown",
            "pdf", "application/pdf");

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public ParsedDocument parse(String fileName, byte[] bytes) {
        String ext = extension(fileName);
        String contentType = SUPPORTED.get(ext);
        if (contentType == null) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                    "Unsupported file type '" + (ext.isEmpty() ? "(none)" : ext) + "'. Supported: .txt, .md, .pdf");
        }
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("EMPTY_DOCUMENT", "The uploaded file is empty");
        }
        ParsedDocument parsed = "pdf".equals(ext) ? parsePdf(bytes) : parseText(bytes, contentType);
        if (parsed.pages().stream().allMatch(p -> p.text().isBlank())) {
            throw ApiException.badRequest("EMPTY_DOCUMENT", "The document contains no extractable text");
        }
        return parsed;
    }

    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private ParsedDocument parseText(byte[] bytes, String contentType) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "File is not valid UTF-8 text");
        }
        if (text.indexOf('\u0000') >= 0) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "File appears to be binary, not text");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return new ParsedDocument(contentType, List.of(new ParsedDocument.Page(null, text)));
    }

    private ParsedDocument parsePdf(byte[] bytes) {
        if (!startsWith(bytes, PDF_MAGIC)) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "File content is not a PDF");
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) {
                throw ApiException.badRequest("UNREADABLE_DOCUMENT", "Encrypted PDFs are not supported");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedDocument.Page> pages = new ArrayList<>();
            for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(new ParsedDocument.Page(i, stripper.getText(pdf)));
            }
            return new ParsedDocument("application/pdf", pages);
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_DOCUMENT", "The PDF could not be read");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
