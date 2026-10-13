package com.salman.ekg.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.salman.ekg.domain.AgentRun;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    @Query("select distinct r from AgentRun r left join fetch r.citations where r.id = :id")
    Optional<AgentRun> findWithCitationsById(@Param("id") Long id);
}
