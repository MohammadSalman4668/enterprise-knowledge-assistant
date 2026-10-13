package com.salman.ekg.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One search, from the search screen or from an agent tool call. {@code resolvedCategory}
 * is the category filter if given, otherwise the category of the top hit, so that
 * "most searched categories" is meaningful even for unfiltered searches.
 */
@Entity
@Table(name = "search_log")
public class SearchLog {

    public enum Source { SEARCH, AGENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 16)
    private Role userRole;

    @Column(nullable = false, length = 1000)
    private String query;

    @Column(name = "category_filter", length = 64)
    private String categoryFilter;

    @Column(name = "resolved_category", length = 64)
    private String resolvedCategory;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "retrieval_mode", nullable = false, length = 16)
    private String retrievalMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Source source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SearchLog() {
    }

    public SearchLog(String username, Role userRole, String query, String categoryFilter, String resolvedCategory,
                     int resultCount, String retrievalMode, Source source, Instant now) {
        this.username = username;
        this.userRole = userRole;
        this.query = query;
        this.categoryFilter = categoryFilter;
        this.resolvedCategory = resolvedCategory;
        this.resultCount = resultCount;
        this.retrievalMode = retrievalMode;
        this.source = source;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public Role getUserRole() { return userRole; }
    public String getQuery() { return query; }
    public String getCategoryFilter() { return categoryFilter; }
    public String getResolvedCategory() { return resolvedCategory; }
    public int getResultCount() { return resultCount; }
    public String getRetrievalMode() { return retrievalMode; }
    public Source getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
