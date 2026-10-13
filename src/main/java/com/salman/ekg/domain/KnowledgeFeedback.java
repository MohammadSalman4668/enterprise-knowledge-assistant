package com.salman.ekg.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A rating on one answer, optionally attributed to one of the documents it cited. */
@Entity
@Table(name = "knowledge_feedback")
public class KnowledgeFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id", nullable = false)
    private AgentRun agentRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private KnowledgeDocument document;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KnowledgeFeedback() {
    }

    public KnowledgeFeedback(AgentRun agentRun, KnowledgeDocument document, String username, String question,
                             int rating, String comment, Instant now) {
        this.agentRun = agentRun;
        this.document = document;
        this.username = username;
        this.question = question;
        this.rating = rating;
        this.comment = comment;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public AgentRun getAgentRun() { return agentRun; }
    public KnowledgeDocument getDocument() { return document; }
    public String getUsername() { return username; }
    public String getQuestion() { return question; }
    public int getRating() { return rating; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
