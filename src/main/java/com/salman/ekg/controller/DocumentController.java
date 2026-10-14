package com.salman.ekg.controller;

import java.io.IOException;
import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.dto.DocumentDtos.ChunkDetail;
import com.salman.ekg.dto.DocumentDtos.DocumentDetail;
import com.salman.ekg.dto.DocumentDtos.DocumentSummary;
import com.salman.ekg.dto.DocumentDtos.PageResponse;
import com.salman.ekg.dto.DocumentDtos.UploadMetadata;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.DocumentService;

@RestController
@RequestMapping("/api/knowledge")
@Validated
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /**
     * Upload a .txt/.md/.pdf file; it is indexed immediately. HR uploads are searchable only after an
     * admin approves them; admin uploads are live at once (the uploader is the approver).
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentSummary> upload(@RequestPart("file") MultipartFile file,
                                                  @Valid @ModelAttribute UploadMetadata metadata,
                                                  Authentication auth) {
        KnowledgeDocument doc = documents.upload(new DocumentService.UploadCommand(metadata.title(),
                metadata.category(), metadata.accessLevel(), file.getOriginalFilename(), bytes(file)),
                CurrentUser.from(auth));
        return ResponseEntity.created(URI.create("/api/knowledge/documents/" + doc.getId()))
                .body(DocumentSummary.of(doc));
    }

    @GetMapping("/documents")
    public PageResponse<DocumentSummary> list(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) DocumentStatus status,
                                              @RequestParam(required = false) AccessLevel accessLevel,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                              Authentication auth) {
        Page<DocumentSummary> result = documents.list(category, status, accessLevel, CurrentUser.from(auth),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id"))));
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/documents/{id}")
    public DocumentDetail get(@PathVariable Long id, Authentication auth) {
        return documents.detail(id, CurrentUser.from(auth));
    }

    @GetMapping("/chunks/{chunkId}")
    public ChunkDetail chunk(@PathVariable Long chunkId, Authentication auth) {
        return documents.chunk(chunkId, CurrentUser.from(auth));
    }

    @PostMapping("/documents/{id}/approve")
    public DocumentSummary approve(@PathVariable Long id, Authentication auth) {
        return DocumentSummary.of(documents.approve(id, CurrentUser.from(auth)));
    }

    @PostMapping("/documents/{id}/reject")
    public DocumentSummary reject(@PathVariable Long id, Authentication auth) {
        return DocumentSummary.of(documents.reject(id, CurrentUser.from(auth)));
    }

    /** Submit updated content; the document is flagged NEEDS_REINDEX and keeps serving its current version. */
    @PostMapping(value = "/documents/{id}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentSummary submitRevision(@PathVariable Long id, @RequestPart("file") MultipartFile file,
                                          Authentication auth) {
        return DocumentSummary.of(documents.submitRevision(id, file.getOriginalFilename(), bytes(file),
                CurrentUser.from(auth)));
    }

    @PutMapping("/documents/{id}/reindex")
    public DocumentSummary reindex(@PathVariable Long id,
                                   @RequestParam(required = false) Integer expectedVersion,
                                   Authentication auth) {
        return DocumentSummary.of(documents.reindex(id, expectedVersion, CurrentUser.from(auth)));
    }

    /** Soft delete (archive). Hard deletion is deliberately not offered: citations must stay resolvable. */
    @PostMapping("/documents/{id}/archive")
    public DocumentSummary archive(@PathVariable Long id, Authentication auth) {
        return DocumentSummary.of(documents.archive(id, CurrentUser.from(auth)));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_DOCUMENT", "Uploaded file could not be read");
        }
    }
}
