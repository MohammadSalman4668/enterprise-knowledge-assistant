package com.salman.ekg.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.domain.Role;
import com.salman.ekg.repository.AnalyticsRepository;

/** Admin-only reports (secured by URL rule in SecurityConfig). */
@RestController
@RequestMapping("/api/knowledge/analytics")
@Validated
public class AnalyticsController {

    private final AnalyticsRepository analytics;

    public AnalyticsController(AnalyticsRepository analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>(analytics.summary());
        summary.put("outcomes", analytics.outcomes());
        return summary;
    }

    @GetMapping("/documents-by-category-status")
    public List<Map<String, Object>> documentsByCategoryAndStatus() {
        return analytics.documentsByCategoryAndStatus();
    }

    @GetMapping("/reindex-queue")
    public List<Map<String, Object>> reindexQueue() {
        return analytics.awaitingReindex();
    }

    @GetMapping("/search-categories")
    public List<Map<String, Object>> searchCategories(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return analytics.searchedCategories(limit);
    }

    @GetMapping("/feedback-by-document")
    public List<Map<String, Object>> feedbackByDocument() {
        return analytics.feedbackByDocument();
    }

    @GetMapping("/accessible-documents")
    public List<Map<String, Object>> accessibleDocuments(@RequestParam Role role) {
        return analytics.accessibleToRole(role.name());
    }
}
