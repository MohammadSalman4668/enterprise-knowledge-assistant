package com.salman.ekg.service;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.domain.AgentCitation;
import com.salman.ekg.domain.AgentRun;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.domain.KnowledgeFeedback;
import com.salman.ekg.dto.FeedbackDtos.FeedbackRequest;
import com.salman.ekg.dto.FeedbackDtos.FeedbackResponse;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.repository.AgentRunRepository;
import com.salman.ekg.repository.KnowledgeDocumentRepository;
import com.salman.ekg.repository.KnowledgeFeedbackRepository;
import com.salman.ekg.security.CurrentUser;

/**
 * Feedback rules:
 * <ul>
 *   <li>Only the person who asked can rate an answer (anyone else gets 404).</li>
 *   <li>A document may only be attributed if the answer actually cited it; when omitted,
 *       the answer's first cited document is used so per-document analytics stay meaningful.</li>
 *   <li>One rating per user per answer: checked here (409) and guaranteed by a unique constraint.</li>
 * </ul>
 */
@Service
public class FeedbackService {

    private final KnowledgeFeedbackRepository feedback;
    private final AgentRunRepository runs;
    private final KnowledgeDocumentRepository documents;
    private final Clock clock;

    public FeedbackService(KnowledgeFeedbackRepository feedback, AgentRunRepository runs,
                           KnowledgeDocumentRepository documents, Clock clock) {
        this.feedback = feedback;
        this.runs = runs;
        this.documents = documents;
        this.clock = clock;
    }

    @Transactional
    public FeedbackResponse submit(FeedbackRequest req, CurrentUser user) {
        AgentRun run = runs.findWithCitationsById(req.answerId())
                .filter(r -> r.getUsername().equals(user.username()))
                .orElseThrow(() -> ApiException.notFound("Answer"));
        if (feedback.existsByAgentRunIdAndUsername(run.getId(), user.username())) {
            throw ApiException.conflict("FEEDBACK_ALREADY_SUBMITTED", "You have already rated this answer");
        }
        Long documentId = req.documentId();
        if (documentId != null) {
            boolean cited = run.getCitations().stream().map(AgentCitation::getDocumentId).anyMatch(documentId::equals);
            if (!cited) {
                throw ApiException.badRequest("DOCUMENT_NOT_CITED", "Feedback can only reference a document cited in the answer");
            }
        } else {
            documentId = run.getCitations().stream().map(AgentCitation::getDocumentId)
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }
        KnowledgeDocument doc = documentId == null ? null : documents.getReferenceById(documentId);
        String comment = req.comment() == null || req.comment().isBlank() ? null : req.comment().strip();
        KnowledgeFeedback saved = feedback.saveAndFlush(new KnowledgeFeedback(run, doc, user.username(),
                run.getQuestion(), req.rating(), comment, clock.instant()));
        return FeedbackResponse.of(saved);
    }
}
