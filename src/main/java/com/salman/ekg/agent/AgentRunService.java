package com.salman.ekg.agent;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.domain.AgentCitation;
import com.salman.ekg.domain.AgentRun;
import com.salman.ekg.dto.AskDtos.AskResponse;
import com.salman.ekg.dto.AskDtos.Citation;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.repository.AgentRunRepository;
import com.salman.ekg.security.CurrentUser;

import tools.jackson.databind.ObjectMapper;

/**
 * Persists runs in a short transaction <em>after</em> the research is done, so no database
 * transaction is held open while a model is being called.
 */
@Service
public class AgentRunService {

    private final AgentRunRepository runs;
    private final ObjectMapper mapper;

    public AgentRunService(AgentRunRepository runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    @Transactional
    public Long save(CurrentUser user, AskResponse response) {
        String question = response.question().length() > 1000 ? response.question().substring(0, 1000) : response.question();
        AgentRun run = new AgentRun(user.username(), user.role(), question, response.answer(), response.outcome(),
                response.mode(), response.degraded(), mapper.writeValueAsString(response), response.latencyMs(),
                response.createdAt() == null ? Instant.now() : response.createdAt());
        List<Citation> citations = response.citations();
        citations.forEach(c -> run.addCitation(new AgentCitation(c.ref(), c.chunkId(), c.documentId(), c.version(),
                c.sectionRef())));
        return runs.save(run).getId();
    }

    /** Re-opens a past answer with its full evidence and trace. Only the asker or an admin may see it. */
    @Transactional(readOnly = true)
    public AskResponse load(Long id, CurrentUser user) {
        AgentRun run = runs.findById(id).orElseThrow(() -> ApiException.notFound("Answer"));
        if (!user.isAdmin() && !run.getUsername().equals(user.username())) {
            throw ApiException.notFound("Answer");
        }
        return mapper.readValue(run.getTraceJson(), AskResponse.class).withAnswerId(run.getId());
    }
}
