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

/**
 * A retrievable passage. It always records the document <em>and</em> the document
 * version it was cut from. The {@code search_vector} column is a database-generated
 * tsvector and is intentionally not mapped.
 */
@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(nullable = false)
    private int version;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "section_ref", length = 200)
    private String sectionRef;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "searchable_text", nullable = false, columnDefinition = "text")
    private String searchableText;

    /** Reference into an external vector store, when one is configured. */
    @Column(name = "embedding_ref", length = 128)
    private String embeddingRef;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(KnowledgeDocument document, int version, int chunkIndex, String sectionRef,
                          Integer pageNumber, String content, String searchableText, Instant now) {
        this.document = document;
        this.version = version;
        this.chunkIndex = chunkIndex;
        this.sectionRef = sectionRef;
        this.pageNumber = pageNumber;
        this.content = content;
        this.searchableText = searchableText;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public KnowledgeDocument getDocument() { return document; }
    public int getVersion() { return version; }
    public int getChunkIndex() { return chunkIndex; }
    public String getSectionRef() { return sectionRef; }
    public Integer getPageNumber() { return pageNumber; }
    public String getContent() { return content; }
    public String getSearchableText() { return searchableText; }
    public String getEmbeddingRef() { return embeddingRef; }
    public boolean isSuperseded() { return superseded; }
    public Instant getCreatedAt() { return createdAt; }
}
