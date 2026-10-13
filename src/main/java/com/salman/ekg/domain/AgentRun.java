package com.salman.ekg.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * The persisted record of one {@code /ask} request: the answer, its outcome, the
 * mode it ran in and the full step-by-step trace. This is the "answer reference"
 * that feedback links to, and it keeps the evidence inspectable after the fact.
 */
@Entity
@Table(name = "agent_run")
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 16)
    private Role userRole;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnswerOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentMode mode;

    @Column(nullable = false)
    private boolean degraded;

    @Column(name = "trace_json", nullable = false, columnDefinition = "text")
    private String traceJson;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "agentRun", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("refLabel")
    private List<AgentCitation> citations = new ArrayList<>();

    protected AgentRun() {
    }

    public AgentRun(String username, Role userRole, String question, String answer, AnswerOutcome outcome,
                    AgentMode mode, boolean degraded, String traceJson, long latencyMs, Instant now) {
        this.username = username;
        this.userRole = userRole;
        this.question = question;
        this.answer = answer;
        this.outcome = outcome;
        this.mode = mode;
        this.degraded = degraded;
        this.traceJson = traceJson;
        this.latencyMs = latencyMs;
        this.createdAt = now;
    }

    public void addCitation(AgentCitation citation) {
        citation.attachTo(this);
        citations.add(citation);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public Role getUserRole() { return userRole; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public AnswerOutcome getOutcome() { return outcome; }
    public AgentMode getMode() { return mode; }
    public boolean isDegraded() { return degraded; }
    public String getTraceJson() { return traceJson; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
    public List<AgentCitation> getCitations() { return citations; }
}
