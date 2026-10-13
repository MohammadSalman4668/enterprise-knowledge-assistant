package com.salman.ekg.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.salman.ekg.domain.AccessLevel;

public final class SearchDtos {

    private SearchDtos() {
    }

    public record SearchRequest(
            @NotBlank @Size(max = 500) String query,
            @Size(max = 64) String category,
            AccessLevel accessLevel,
            @Min(1) @Max(50) Integer limit) {
    }

    public record SearchHit(
            long chunkId, long documentId, String title, String category, AccessLevel accessLevel, int version,
            int chunkIndex, String sectionRef, Integer pageNumber, String location, String snippet, double score,
            List<String> matchedTerms) {
    }

    public record SearchResponse(
            String query, String retrievalMode, boolean fallbackUsed, List<String> warnings, int total,
            List<SearchHit> results) {
    }
}
