package com.salman.ekg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A chunk cited in an answer, pinned to the exact document version that was used. */
@Entity
@Table(name = "agent_citation")
public class AgentCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id", nullable = false)
    private AgentRun agentRun;

    @Column(name = "ref_label", nullable = false, length = 8)
    private String refLabel;

    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "document_version", nullable = false)
    private int documentVersion;

    @Column(name = "section_ref", length = 200)
    private String sectionRef;

    protected AgentCitation() {
    }

    public AgentCitation(String refLabel, Long chunkId, Long documentId, int documentVersion, String sectionRef) {
        this.refLabel = refLabel;
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.sectionRef = sectionRef;
    }

    void attachTo(AgentRun run) {
        this.agentRun = run;
    }

    public Long getId() { return id; }
    public AgentRun getAgentRun() { return agentRun; }
    public String getRefLabel() { return refLabel; }
    public Long getChunkId() { return chunkId; }
    public Long getDocumentId() { return documentId; }
    public int getDocumentVersion() { return documentVersion; }
    public String getSectionRef() { return sectionRef; }
}
