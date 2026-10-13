package com.salman.ekg.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.salman.ekg.domain.SearchLog;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {
}
