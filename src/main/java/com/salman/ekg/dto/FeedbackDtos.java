package com.salman.ekg.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.salman.ekg.domain.KnowledgeFeedback;

/**
 * Request and response bodies of {@code POST /api/knowledge/feedback}.
 */
public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    public record FeedbackRequest(
            @NotNull Long answerId,
            Long documentId,
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 1000) String comment) {
    }

    public record FeedbackResponse(Long id, Long answerId, Long documentId, String documentTitle, int rating,
                                   String comment, String question, Instant createdAt) {

        public static FeedbackResponse of(KnowledgeFeedback f) {
            return new FeedbackResponse(f.getId(), f.getAgentRun().getId(),
                    f.getDocument() == null ? null : f.getDocument().getId(),
                    f.getDocument() == null ? null : f.getDocument().getTitle(),
                    f.getRating(), f.getComment(), f.getQuestion(), f.getCreatedAt());
        }
    }
}
