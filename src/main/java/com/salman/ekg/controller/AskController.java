package com.salman.ekg.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.agent.AgentModels;
import com.salman.ekg.agent.AgentRunService;
import com.salman.ekg.agent.ResearchAgent;
import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AgentMode;
import com.salman.ekg.dto.AskDtos.AgentStatus;
import com.salman.ekg.dto.AskDtos.AskRequest;
import com.salman.ekg.dto.AskDtos.AskResponse;
import com.salman.ekg.retrieval.Retriever;
import com.salman.ekg.security.CurrentUser;

@RestController
@RequestMapping("/api/knowledge")
public class AskController {

    private final ResearchAgent agent;
    private final AgentRunService runs;
    private final List<Retriever> retrievers;
    private final KnowledgeProperties props;

    public AskController(ResearchAgent agent, AgentRunService runs, List<Retriever> retrievers,
                         KnowledgeProperties props) {
        this.agent = agent;
        this.runs = runs;
        this.retrievers = retrievers;
        this.props = props;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request, Authentication auth) {
        return agent.ask(request, CurrentUser.from(auth));
    }

    /** Re-open a past answer with its evidence and trace (asker or admin only). */
    @GetMapping("/answers/{id}")
    public AskResponse answer(@PathVariable Long id, Authentication auth) {
        return runs.load(id, CurrentUser.from(auth));
    }

    @GetMapping("/agent/status")
    public AgentStatus status() {
        AgentModels models = agent.models();
        boolean simulated = models.mode() == AgentMode.SIMULATED;
        return new AgentStatus(models.mode(), models.modelName(), simulated,
                retrievers.stream().map(Retriever::name).toList(),
                props.agent().maxToolCalls(), props.agent().minCoverage(),
                simulated ? "SIMULATION: no AI model is called. Research and answers are produced by deterministic "
                        + "Java code (keyword search + extractive quoting). Set ANTHROPIC_API_KEY to enable the live model."
                        : "Live model " + models.modelName() + " chooses searches and phrases answers; Java enforces "
                        + "permissions, evidence sufficiency and citation validation.");
    }
}
