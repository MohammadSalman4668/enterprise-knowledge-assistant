package com.salman.ekg.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.salman.ekg.domain.SearchLog;

/**
 * Search history used by the analytics reports (most searched categories).
 */
public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {
}
