package com.salman.ekg.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.KnowledgeDocument;

public interface KnowledgeDocumentRepository
        extends JpaRepository<KnowledgeDocument, Long>, JpaSpecificationExecutor<KnowledgeDocument> {

    boolean existsByContentHashAndStatusNotIn(String contentHash, Collection<DocumentStatus> statuses);

    boolean existsByContentHashAndStatusNotInAndIdNot(String contentHash, Collection<DocumentStatus> statuses, Long id);
}
