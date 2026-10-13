package com.salman.ekg.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.salman.ekg.domain.KnowledgeFeedback;

public interface KnowledgeFeedbackRepository extends JpaRepository<KnowledgeFeedback, Long> {

    boolean existsByAgentRunIdAndUsername(Long agentRunId, String username);

    List<KnowledgeFeedback> findByAgentRunId(Long agentRunId);
}
