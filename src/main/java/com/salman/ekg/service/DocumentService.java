package com.salman.ekg.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.DocumentVersion;
import com.salman.ekg.domain.KnowledgeChunk;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.domain.RevisionStatus;
import com.salman.ekg.dto.DocumentDtos.ChunkDetail;
import com.salman.ekg.dto.DocumentDtos.ChunkSummary;
import com.salman.ekg.dto.DocumentDtos.DocumentDetail;
import com.salman.ekg.dto.DocumentDtos.DocumentSummary;
import com.salman.ekg.dto.DocumentDtos.VersionInfo;
import com.salman.ekg.embedding.EmbeddingIndexer;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.ingestion.Chunker;
import com.salman.ekg.ingestion.DocumentParser;
import com.salman.ekg.ingestion.ParsedDocument;
import com.salman.ekg.repository.DocumentVersionRepository;
import com.salman.ekg.repository.KnowledgeChunkRepository;
import com.salman.ekg.repository.KnowledgeDocumentRepository;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.text.TextAnalysis;

/**
 * Document lifecycle: upload, approval gate, revisions, re-index and archive.
 *
 * <p>Every state-changing method is one transaction, so a document is never left with
 * half its chunks written or with chunks of two versions both live. Concurrency is
 * handled in three layers: (1) state checks here give friendly 409s, (2) the JPA
 * {@code @Version} on the document rejects a second concurrent writer, and (3) unique
 * indexes in the database are the final race-proof guarantee.</p>
 */
@Service
public class DocumentService {

    /** Content that blocks a duplicate upload: everything except archived/rejected documents. */
    private static final Set<DocumentStatus> NOT_LIVE = EnumSet.of(DocumentStatus.ARCHIVED, DocumentStatus.REJECTED);

    public record UploadCommand(String title, String category, AccessLevel accessLevel, String fileName, byte[] bytes) {}

    private final KnowledgeDocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final KnowledgeChunkRepository chunks;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final AccessPolicyService accessPolicy;
    private final KnowledgeProperties props;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public DocumentService(KnowledgeDocumentRepository documents, DocumentVersionRepository versions,
                           KnowledgeChunkRepository chunks, DocumentParser parser, Chunker chunker,
                           AccessPolicyService accessPolicy, KnowledgeProperties props, Clock clock,
                           ApplicationEventPublisher events) {
        this.documents = documents;
        this.versions = versions;
        this.chunks = chunks;
        this.parser = parser;
        this.chunker = chunker;
        this.accessPolicy = accessPolicy;
        this.props = props;
        this.clock = clock;
        this.events = events;
    }

    // ------------------------------------------------------------------ commands

    /** Validates, parses, chunks and stores a new document as version 1, awaiting approval. */
    @Transactional
    public KnowledgeDocument upload(UploadCommand cmd, CurrentUser user) {
        if (!user.role().canContribute()) {
            throw ApiException.forbidden("FORBIDDEN", "Your role cannot upload documents");
        }
        if (!accessPolicy.canRead(user.role(), cmd.accessLevel())) {
            throw ApiException.forbidden("ACCESS_LEVEL_NOT_PERMITTED",
                    "You cannot upload a document at access level " + cmd.accessLevel() + " because you could not read it");
        }
        checkSize(cmd.bytes());
        ParsedDocument parsed = parser.parse(cmd.fileName(), cmd.bytes());
        String hash = sha256(parsed.fullText());
        if (documents.existsByContentHashAndStatusNotIn(hash, NOT_LIVE)) {
            throw ApiException.conflict("DUPLICATE_DOCUMENT", "A document with identical content already exists");
        }
        Instant now = clock.instant();
        KnowledgeDocument doc = documents.save(new KnowledgeDocument(cmd.title().strip(), cmd.category().strip(),
                user.username(), cmd.accessLevel(), safeFileName(cmd.fileName()), parsed.contentType(), hash, now));
        versions.save(new DocumentVersion(doc, 1, hash, parsed.fullText(), doc.getFileName(), parsed.contentType(),
                RevisionStatus.CURRENT, user.username(), now));
        writeChunks(doc, 1, parsed, now);
        if (user.isAdmin()) {
            // The approver is the uploader: a separate approval step would add nothing.
            doc.changeStatus(DocumentStatus.ACTIVE, now);
        }
        return doc;
    }

    /** Human approval gate: only approved documents become searchable. */
    @Transactional
    public KnowledgeDocument approve(Long id, CurrentUser admin) {
        KnowledgeDocument doc = loadForUpdate(id);
        requireStatus(doc, EnumSet.of(DocumentStatus.PENDING_APPROVAL), "approve");
        doc.changeStatus(DocumentStatus.ACTIVE, clock.instant());
        return doc;
    }

    @Transactional
    public KnowledgeDocument reject(Long id, CurrentUser admin) {
        KnowledgeDocument doc = loadForUpdate(id);
        requireStatus(doc, EnumSet.of(DocumentStatus.PENDING_APPROVAL), "reject");
        doc.changeStatus(DocumentStatus.REJECTED, clock.instant());
        return doc;
    }

    /**
     * Stores new content as a PENDING revision and flags the document NEEDS_REINDEX.
     * The approved current version keeps being served until an admin re-indexes.
     */
    @Transactional
    public KnowledgeDocument submitRevision(Long id, String fileName, byte[] bytes, CurrentUser user) {
        KnowledgeDocument doc = loadForUpdate(id);
        if (!user.isAdmin() && !doc.getOwner().equals(user.username())) {
            throw ApiException.forbidden("NOT_DOCUMENT_OWNER", "Only the owner or an admin can submit a revision");
        }
        if (!accessPolicy.canRead(user.role(), doc.getAccessLevel())) {
            throw ApiException.notFound("Document");
        }
        requireStatus(doc, EnumSet.of(DocumentStatus.ACTIVE, DocumentStatus.NEEDS_REINDEX), "revise");
        checkSize(bytes);
        ParsedDocument parsed = parser.parse(fileName, bytes);
        String hash = sha256(parsed.fullText());
        if (hash.equals(doc.getContentHash())) {
            throw ApiException.conflict("NO_CHANGES", "The revision is identical to the current version");
        }
        if (versions.findByDocumentIdAndStatus(id, RevisionStatus.PENDING).isPresent()) {
            throw ApiException.conflict("REVISION_ALREADY_PENDING", "A revision is already waiting to be re-indexed");
        }
        if (documents.existsByContentHashAndStatusNotInAndIdNot(hash, NOT_LIVE, id)) {
            throw ApiException.conflict("DUPLICATE_DOCUMENT", "Another document already has identical content");
        }
        Instant now = clock.instant();
        int next = versions.findMaxVersion(id) + 1;
        versions.save(new DocumentVersion(doc, next, hash, parsed.fullText(), safeFileName(fileName),
                parsed.contentType(), RevisionStatus.PENDING, user.username(), now));
        doc.changeStatus(DocumentStatus.NEEDS_REINDEX, now);
        return doc;
    }

    /**
     * Builds a new version: from the pending revision if there is one, otherwise from the
     * current text (e.g. after a chunking change). All previous chunks are marked superseded
     * in the same transaction, so obsolete text can never be served as current.
     *
     * @param expectedVersion optional optimistic check from the client ("I am re-indexing v3")
     */
    @Transactional
    public KnowledgeDocument reindex(Long id, Integer expectedVersion, CurrentUser admin) {
        KnowledgeDocument doc = loadForUpdate(id);
        requireStatus(doc, EnumSet.of(DocumentStatus.ACTIVE, DocumentStatus.NEEDS_REINDEX), "re-index");
        if (expectedVersion != null && expectedVersion != doc.getCurrentVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "Expected version " + expectedVersion
                    + " but the document is at version " + doc.getCurrentVersion());
        }
        Instant now = clock.instant();
        long lockVersionAtStart = doc.getLockVersion();
        DocumentVersion current = versions.findByDocumentIdAndStatus(id, RevisionStatus.CURRENT)
                .orElseThrow(() -> new IllegalStateException("Document " + id + " has no current revision"));
        DocumentVersion source = versions.findByDocumentIdAndStatus(id, RevisionStatus.PENDING).orElse(null);

        current.setStatus(RevisionStatus.SUPERSEDED);
        versions.saveAndFlush(current); // free the "one CURRENT per document" slot before promoting
        if (source == null) {
            source = versions.save(new DocumentVersion(doc, versions.findMaxVersion(id) + 1, current.getContentHash(),
                    current.getRawText(), current.getFileName(), current.getContentType(), RevisionStatus.CURRENT,
                    admin.username(), now));
        } else {
            source.setStatus(RevisionStatus.CURRENT);
        }

        chunks.supersedeLiveChunks(id);
        doc = documents.findById(id).orElseThrow(); // re-attach after the bulk update cleared the context
        if (doc.getLockVersion() != lockVersionAtStart) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "The document changed during re-indexing; retry");
        }
        ParsedDocument parsed = new ParsedDocument(source.getContentType(),
                List.of(new ParsedDocument.Page(null, source.getRawText())));
        writeChunks(doc, source.getVersion(), parsed, now);
        doc.promoteRevision(source.getVersion(), source.getFileName(), source.getContentType(),
                source.getContentHash(), now);
        return documents.saveAndFlush(doc);
    }

    /** Soft delete: the document and its chunks leave search immediately but stay for audit. */
    @Transactional
    public KnowledgeDocument archive(Long id, CurrentUser admin) {
        KnowledgeDocument doc = loadForUpdate(id);
        if (doc.getStatus() == DocumentStatus.ARCHIVED) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "Document is already archived");
        }
        doc.changeStatus(DocumentStatus.ARCHIVED, clock.instant());
        return doc;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<DocumentSummary> list(String category, DocumentStatus status, AccessLevel accessLevel,
                                      CurrentUser user, Pageable pageable) {
        Set<AccessLevel> levels = accessPolicy.allowedLevels(user.role());
        if (levels.isEmpty() || (accessLevel != null && !levels.contains(accessLevel))) {
            return Page.empty(pageable);
        }
        Specification<KnowledgeDocument> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(root.get("accessLevel").in(levels));
            if (category != null && !category.isBlank()) {
                p.add(cb.equal(cb.lower(root.get("category")), category.strip().toLowerCase()));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (accessLevel != null) {
                p.add(cb.equal(root.get("accessLevel"), accessLevel));
            }
            if (!user.isAdmin()) {
                p.add(cb.or(root.get("status").in(DocumentStatus.SEARCHABLE),
                        cb.equal(root.get("owner"), user.username())));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return documents.findAll(spec, pageable).map(DocumentSummary::of);
    }

    @Transactional(readOnly = true)
    public DocumentDetail detail(Long id, CurrentUser user) {
        KnowledgeDocument doc = loadVisible(id, user);
        List<KnowledgeChunk> live = chunks.findByDocumentIdAndVersionOrderByChunkIndex(id, doc.getCurrentVersion());
        List<DocumentVersion> history = versions.findByDocumentIdOrderByVersionDesc(id);
        Integer pending = history.stream().filter(v -> v.getStatus() == RevisionStatus.PENDING)
                .map(DocumentVersion::getVersion).findFirst().orElse(null);
        return new DocumentDetail(DocumentSummary.of(doc), live.size(), pending,
                history.stream().map(VersionInfo::of).toList(),
                live.stream().map(ChunkSummary::of).toList());
    }

    /** Source preview. Obsolete chunks stay viewable (flagged not current) so past answers remain inspectable. */
    @Transactional(readOnly = true)
    public ChunkDetail chunk(Long chunkId, CurrentUser user) {
        KnowledgeChunk chunk = chunks.findWithDocumentById(chunkId).orElseThrow(() -> ApiException.notFound("Chunk"));
        if (!isVisible(chunk.getDocument(), user)) {
            throw ApiException.notFound("Chunk");
        }
        return ChunkDetail.of(chunk);
    }

    /**
     * Visibility rule shared by every read: the role must be allowed the access level, and
     * non-admins only see searchable documents (or ones they own). Anything else is a 404,
     * so the existence of a restricted document is not revealed.
     */
    public boolean isVisible(KnowledgeDocument doc, CurrentUser user) {
        if (!accessPolicy.canRead(user.role(), doc.getAccessLevel())) {
            return false;
        }
        return user.isAdmin() || doc.getStatus().isSearchable() || doc.getOwner().equals(user.username());
    }

    @Transactional(readOnly = true)
    public KnowledgeDocument loadVisible(Long id, CurrentUser user) {
        KnowledgeDocument doc = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
        if (!isVisible(doc, user)) {
            throw ApiException.notFound("Document");
        }
        return doc;
    }

    // ------------------------------------------------------------------ helpers

    private void writeChunks(KnowledgeDocument doc, int version, ParsedDocument parsed, Instant now) {
        List<Chunker.ChunkDraft> drafts = chunker.chunk(parsed);
        if (drafts.isEmpty()) {
            throw ApiException.badRequest("EMPTY_DOCUMENT", "The document produced no indexable text");
        }
        List<KnowledgeChunk> entities = drafts.stream()
                .map(d -> new KnowledgeChunk(doc, version, d.index(), d.sectionRef(), d.pageNumber(), d.content(),
                        searchableText(doc, d), now))
                .toList();
        chunks.saveAll(entities);
        // Embeddings are computed after commit, outside this transaction (see EmbeddingIndexer).
        events.publishEvent(new EmbeddingIndexer.DocumentIndexedEvent(doc.getId()));
    }

    private static String searchableText(KnowledgeDocument doc, Chunker.ChunkDraft d) {
        StringBuilder sb = new StringBuilder(doc.getTitle()).append('\n');
        if (d.sectionRef() != null) {
            sb.append(d.sectionRef()).append('\n');
        }
        return sb.append(d.content()).toString();
    }

    private KnowledgeDocument loadForUpdate(Long id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
    }

    private static void requireStatus(KnowledgeDocument doc, Set<DocumentStatus> allowed, String action) {
        if (!allowed.contains(doc.getStatus())) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                    "Cannot " + action + " a document in status " + doc.getStatus());
        }
    }

    private void checkSize(byte[] bytes) {
        if (bytes != null && bytes.length > props.ingestion().maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds the upload limit");
        }
    }

    private static String safeFileName(String fileName) {
        String name = fileName == null ? "upload" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(TextAnalysis.normalizeWhitespace(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
