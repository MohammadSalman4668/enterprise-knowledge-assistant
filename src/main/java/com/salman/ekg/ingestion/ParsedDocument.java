package com.salman.ekg.ingestion;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracted text of an uploaded file. PDFs keep one {@link Page} per physical page so
 * citations can reference page numbers; text formats are a single page with no number.
 */
public record ParsedDocument(String contentType, List<Page> pages) {

    public record Page(Integer pageNumber, String text) {}

    public String fullText() {
        return pages.stream().map(Page::text).collect(Collectors.joining("\n\n"));
    }
}
