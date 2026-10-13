package com.salman.ekg.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.KnowledgeChunk;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByDocumentIdAndVersionOrderByChunkIndex(Long documentId, int version);

    long countByDocumentIdAndVersion(Long documentId, int version);

    @Query("select c from KnowledgeChunk c join fetch c.document where c.id = :id")
    Optional<KnowledgeChunk> findWithDocumentById(@Param("id") Long id);

    /** Marks every live chunk of a document as obsolete before a new version is indexed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update KnowledgeChunk c set c.superseded = true where c.document.id = :documentId and c.superseded = false")
    int supersedeLiveChunks(@Param("documentId") Long documentId);

    /**
     * Re-validates retriever output against the database: keeps only chunks that are of the
     * document's current version, not superseded, in a searchable document and readable at the
     * caller's access levels. Applied to every retriever so a stale or misconfigured index
     * (e.g. an external vector store) can never surface obsolete or forbidden content.
     */
    @Query("""
            select c.id from KnowledgeChunk c join c.document d
            where c.id in :ids
              and c.superseded = false
              and c.version = d.currentVersion
              and d.status in :statuses
              and d.accessLevel in :levels
            """)
    List<Long> filterLiveAndAccessible(@Param("ids") Collection<Long> ids,
                                       @Param("statuses") Collection<DocumentStatus> statuses,
                                       @Param("levels") Collection<AccessLevel> levels);
}
