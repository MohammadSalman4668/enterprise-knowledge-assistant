package com.salman.ekg.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.salman.ekg.domain.DocumentVersion;
import com.salman.ekg.domain.RevisionStatus;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByDocumentIdAndStatus(Long documentId, RevisionStatus status);

    List<DocumentVersion> findByDocumentIdOrderByVersionDesc(Long documentId);

    @Query("select coalesce(max(v.version), 0) from DocumentVersion v where v.document.id = :documentId")
    int findMaxVersion(@Param("documentId") Long documentId);
}
