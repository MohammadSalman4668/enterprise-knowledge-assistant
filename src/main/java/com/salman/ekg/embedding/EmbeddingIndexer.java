package com.salman.ekg.embedding;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.salman.ekg.config.KnowledgeProperties;

/**
 * Keeps {@code chunk_embedding} complete: embeds every live chunk that has no vector for
 * the configured model. Runs after a document's chunks are committed (upload / re-index),
 * on a schedule, and at startup. It never runs inside the upload transaction, so a slow or
 * offline embedding model can never block or fail an upload; such chunks are simply found
 * by keyword search until they are embedded.
 */
@Component
public class EmbeddingIndexer {

    /** Published by DocumentService when new chunks were written. */
    public record DocumentIndexedEvent(Long documentId) {}

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);

    private final EmbeddingService embeddings;
    private final ChunkEmbeddingRepository vectors;
    private final ExecutorService executor;
    private final KnowledgeProperties.Embedding config;
    private final AtomicBoolean running = new AtomicBoolean();

    public EmbeddingIndexer(EmbeddingService embeddings, ChunkEmbeddingRepository vectors, ExecutorService knowledgeExecutor,
                            KnowledgeProperties props) {
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.executor = knowledgeExecutor;
        this.config = props.embedding();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentIndexed(DocumentIndexedEvent event) {
        if (!embeddings.enabled()) {
            return;
        }
        if (config.asyncIndexing()) {
            executor.submit(this::backfill);
        } else {
            backfill();
        }
    }

    /**
     * Embeds all chunks still missing a vector, in batches. Returns how many were embedded.
     * Only one backfill runs at a time; a concurrent call returns 0 immediately.
     */
    public int backfill() {
        if (!embeddings.enabled() || !running.compareAndSet(false, true)) {
            return 0;
        }
        int done = 0;
        try {
            List<ChunkEmbeddingRepository.ChunkText> batch;
            while (!(batch = vectors.findMissing(config.model(), Math.max(1, config.batchSize()))).isEmpty()) {
                List<float[]> vs = embeddings.embedDocuments(batch.stream().map(ChunkEmbeddingRepository.ChunkText::text).toList());
                for (int i = 0; i < batch.size(); i++) {
                    vectors.upsert(batch.get(i).chunkId(), config.model(), vs.get(i));
                }
                done += batch.size();
            }
            if (done > 0) {
                log.info("Embedded {} chunk(s) with {}", done, config.model());
            }
        } catch (RuntimeException e) {
            log.warn("Embedding backfill stopped after {} chunk(s): {} (keyword search still works; will retry)",
                    done, e.toString());
        } finally {
            running.set(false);
        }
        return done;
    }
}
