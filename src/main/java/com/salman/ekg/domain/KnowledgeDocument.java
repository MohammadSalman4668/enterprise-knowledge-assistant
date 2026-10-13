package com.salman.ekg.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Metadata for one logical document. {@code currentVersion} names the revision whose
 * chunks are searchable; older chunks remain stored but are marked superseded.
 * {@code lockVersion} gives optimistic locking so two concurrent re-index or approval
 * requests cannot both succeed against the same state.
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 64)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 16)
    private AccessLevel accessLevel;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(String title, String category, String owner, AccessLevel accessLevel,
                             String fileName, String contentType, String contentHash, Instant now) {
        this.title = title;
        this.category = category;
        this.owner = owner;
        this.accessLevel = accessLevel;
        this.fileName = fileName;
        this.contentType = contentType;
        this.contentHash = contentHash;
        this.currentVersion = 1;
        this.status = DocumentStatus.PENDING_APPROVAL;
        this.uploadedAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getCategory() { return category; }
    public String getOwner() { return owner; }
    public AccessLevel getAccessLevel() { return accessLevel; }
    public int getCurrentVersion() { return currentVersion; }
    public DocumentStatus getStatus() { return status; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getContentHash() { return contentHash; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getLockVersion() { return lockVersion; }

    public void changeStatus(DocumentStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    /** Points the document at a newly indexed revision. */
    public void promoteRevision(int version, String fileName, String contentType, String contentHash, Instant now) {
        this.currentVersion = version;
        this.fileName = fileName;
        this.contentType = contentType;
        this.contentHash = contentHash;
        this.status = DocumentStatus.ACTIVE;
        this.updatedAt = now;
    }
}
