package com.salman.ekg.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.DocumentVersion;
import com.salman.ekg.domain.KnowledgeChunk;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.domain.RevisionStatus;

/** Request/response types for the document management API. */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    /** Metadata fields of the multipart upload (the file itself is a separate part). */
    public record UploadMetadata(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9 _-]*$", message = "must start with a letter and contain only letters, digits, spaces, '-' or '_'")
            String category,
            @NotNull AccessLevel accessLevel) {
    }

    public record DocumentSummary(
            Long id, String title, String category, String owner, AccessLevel accessLevel, int version,
            DocumentStatus status, String fileName, String contentType, Instant uploadedAt, Instant updatedAt) {

        public static DocumentSummary of(KnowledgeDocument d) {
            return new DocumentSummary(d.getId(), d.getTitle(), d.getCategory(), d.getOwner(), d.getAccessLevel(),
                    d.getCurrentVersion(), d.getStatus(), d.getFileName(), d.getContentType(),
                    d.getUploadedAt(), d.getUpdatedAt());
        }
    }

    public record VersionInfo(int version, RevisionStatus status, String fileName, String createdBy, Instant createdAt) {

        public static VersionInfo of(DocumentVersion v) {
            return new VersionInfo(v.getVersion(), v.getStatus(), v.getFileName(), v.getCreatedBy(), v.getCreatedAt());
        }
    }

    public record DocumentDetail(
            DocumentSummary document, long chunkCount, Integer pendingRevision, List<VersionInfo> versions,
            List<ChunkSummary> chunks) {
    }

    public record ChunkSummary(Long chunkId, int chunkIndex, String sectionRef, Integer pageNumber, String preview) {

        public static ChunkSummary of(KnowledgeChunk c) {
            String content = c.getContent();
            String preview = content.length() > 160 ? content.substring(0, 160) + "…" : content;
            return new ChunkSummary(c.getId(), c.getChunkIndex(), c.getSectionRef(), c.getPageNumber(), preview);
        }
    }

    /** Full chunk for the source-preview screen. {@code current=false} marks obsolete evidence. */
    public record ChunkDetail(
            Long chunkId, Long documentId, String documentTitle, String category, AccessLevel accessLevel,
            int version, int documentCurrentVersion, boolean current, int chunkIndex, String sectionRef,
            Integer pageNumber, String content) {

        public static ChunkDetail of(KnowledgeChunk c) {
            KnowledgeDocument d = c.getDocument();
            boolean current = !c.isSuperseded() && c.getVersion() == d.getCurrentVersion();
            return new ChunkDetail(c.getId(), d.getId(), d.getTitle(), d.getCategory(), d.getAccessLevel(),
                    c.getVersion(), d.getCurrentVersion(), current, c.getChunkIndex(), c.getSectionRef(),
                    c.getPageNumber(), c.getContent());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
