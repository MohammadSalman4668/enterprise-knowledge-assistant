package com.salman.ekg.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One stored revision of a document's extracted text (audit trail and re-index source). */
@Entity
@Table(name = "document_version")
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(nullable = false)
    private int version;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RevisionStatus status;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentVersion() {
    }

    public DocumentVersion(KnowledgeDocument document, int version, String contentHash, String rawText,
                           String fileName, String contentType, RevisionStatus status, String createdBy, Instant now) {
        this.document = document;
        this.version = version;
        this.contentHash = contentHash;
        this.rawText = rawText;
        this.fileName = fileName;
        this.contentType = contentType;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public KnowledgeDocument getDocument() { return document; }
    public int getVersion() { return version; }
    public String getContentHash() { return contentHash; }
    public String getRawText() { return rawText; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public RevisionStatus getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(RevisionStatus status) {
        this.status = status;
    }
}
