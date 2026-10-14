package com.salman.ekg.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AgentCitation;
import com.salman.ekg.domain.AgentMode;
import com.salman.ekg.domain.AgentRun;
import com.salman.ekg.domain.AnswerOutcome;
import com.salman.ekg.domain.DocumentStatus;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.domain.KnowledgeFeedback;
import com.salman.ekg.domain.Role;
import com.salman.ekg.dto.FeedbackDtos.FeedbackRequest;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.ingestion.Chunker;
import com.salman.ekg.ingestion.DocumentParser;
import com.salman.ekg.repository.AgentRunRepository;
import com.salman.ekg.repository.DocumentVersionRepository;
import com.salman.ekg.repository.KnowledgeChunkRepository;
import com.salman.ekg.repository.KnowledgeDocumentRepository;
import com.salman.ekg.repository.KnowledgeFeedbackRepository;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.support.TestProps;

class ServiceRulesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);

    private static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            return e.getCode();
        }
    }

    @Nested
    class DocumentServiceRules {
        private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        private final AccessPolicyService policy = mock(AccessPolicyService.class);
        private DocumentService service;

        @BeforeEach
        void setUp() {
            service = new DocumentService(documents, versions, chunks, new DocumentParser(),
                    new Chunker(TestProps.defaults()), policy, TestProps.defaults(), CLOCK);
            when(policy.canRead(Role.HR, AccessLevel.INTERNAL)).thenReturn(true);
            when(policy.canRead(Role.HR, AccessLevel.CONFIDENTIAL)).thenReturn(false);
            when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        private DocumentService.UploadCommand cmd(AccessLevel level, String content) {
            return new DocumentService.UploadCommand("T", "IT", level, "t.md", content.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void employeesCannotUpload() {
            assertThat(code(() -> service.upload(cmd(AccessLevel.INTERNAL, "# A\n\nx"), new CurrentUser("e", Role.EMPLOYEE))))
                    .isEqualTo("FORBIDDEN");
            verify(documents, never()).save(any());
        }

        @Test
        void cannotUploadAtUnreadableLevel() {
            assertThat(code(() -> service.upload(cmd(AccessLevel.CONFIDENTIAL, "# A\n\nx"), new CurrentUser("e", Role.HR))))
                    .isEqualTo("ACCESS_LEVEL_NOT_PERMITTED");
        }

        @Test
        void duplicateContentIsRejectedBeforeSaving() {
            when(documents.existsByContentHashAndStatusNotIn(anyString(), anyCollection())).thenReturn(true);
            assertThat(code(() -> service.upload(cmd(AccessLevel.INTERNAL, "# A\n\nx"), new CurrentUser("e", Role.HR))))
                    .isEqualTo("DUPLICATE_DOCUMENT");
            verify(documents, never()).save(any());
        }

        @Test
        @DisplayName("New uploads start as version 1 awaiting approval, with chunks written in the same call")
        void uploadCreatesPendingVersionOne() {
            KnowledgeDocument doc = service.upload(cmd(AccessLevel.INTERNAL, "# Title\n\nSome content here."),
                    new CurrentUser("hana", Role.HR));
            assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
            assertThat(doc.getCurrentVersion()).isEqualTo(1);
            assertThat(doc.getOwner()).isEqualTo("hana");
            assertThat(doc.getContentHash()).hasSize(64);
            verify(versions).save(any());
            verify(chunks).saveAll(any());
        }

        @Test
        void approvingTwiceIsAnInvalidTransition() {
            KnowledgeDocument doc = new KnowledgeDocument("T", "IT", "hana", AccessLevel.INTERNAL, "t.md", "text/markdown", "h", CLOCK.instant());
            doc.changeStatus(DocumentStatus.ACTIVE, CLOCK.instant());
            when(documents.findById(1L)).thenReturn(Optional.of(doc));
            assertThat(code(() -> service.approve(1L, new CurrentUser("admin", Role.ADMIN)))).isEqualTo("INVALID_STATE_TRANSITION");
        }

        @Test
        void sha256IgnoresWhitespaceOnlyChanges() {
            assertThat(DocumentService.sha256("a  b\n c")).isEqualTo(DocumentService.sha256("a b c"));
            assertThat(DocumentService.sha256("a b c")).isNotEqualTo(DocumentService.sha256("a b d"));
        }
    }

    @Nested
    class FeedbackRules {
        private final KnowledgeFeedbackRepository feedback = mock(KnowledgeFeedbackRepository.class);
        private final AgentRunRepository runs = mock(AgentRunRepository.class);
        private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        private final FeedbackService service = new FeedbackService(feedback, runs, documents, CLOCK);
        private final CurrentUser emma = new CurrentUser("employee", Role.EMPLOYEE);

        private AgentRun run() {
            AgentRun run = new AgentRun("employee", Role.EMPLOYEE, "q?", "a", AnswerOutcome.ANSWERED, AgentMode.SIMULATED,
                    false, "{}", 5, CLOCK.instant());
            run.addCitation(new AgentCitation("E1", 11L, 7L, 1, "S"));
            return run;
        }

        @Test
        void someoneElsesAnswerIsNotFound() {
            when(runs.findWithCitationsById(1L)).thenReturn(Optional.of(run()));
            assertThat(code(() -> service.submit(new FeedbackRequest(1L, null, 5, null), new CurrentUser("other", Role.HR))))
                    .isEqualTo("NOT_FOUND");
        }

        @Test
        void documentMustHaveBeenCited() {
            when(runs.findWithCitationsById(1L)).thenReturn(Optional.of(run()));
            assertThat(code(() -> service.submit(new FeedbackRequest(1L, 99L, 5, null), emma))).isEqualTo("DOCUMENT_NOT_CITED");
        }

        @Test
        void duplicateFeedbackIsAConflict() {
            when(runs.findWithCitationsById(1L)).thenReturn(Optional.of(run()));
            when(feedback.existsByAgentRunIdAndUsername(any(), any())).thenReturn(true);
            assertThat(code(() -> service.submit(new FeedbackRequest(1L, null, 5, null), emma))).isEqualTo("FEEDBACK_ALREADY_SUBMITTED");
        }

        @Test
        @DisplayName("Without an explicit document, feedback is linked to the first cited document")
        void defaultsToFirstCitedDocument() {
            when(runs.findWithCitationsById(1L)).thenReturn(Optional.of(run()));
            KnowledgeDocument doc = new KnowledgeDocument("Leave", "HR", "hr", AccessLevel.INTERNAL, "l.md", "text/markdown", "h", CLOCK.instant());
            when(documents.getReferenceById(7L)).thenReturn(doc);
            when(feedback.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            service.submit(new FeedbackRequest(1L, null, 3, "  ok  "), emma);
            verify(feedback).saveAndFlush(org.mockito.ArgumentMatchers.argThat((KnowledgeFeedback f) ->
                    f.getDocument() == doc && f.getRating() == 3 && "ok".equals(f.getComment()) && "q?".equals(f.getQuestion())));
        }
    }

    @Test
    void searchableStatuses() {
        assertThat(DocumentStatus.SEARCHABLE).isEqualTo(EnumSet.of(DocumentStatus.ACTIVE, DocumentStatus.NEEDS_REINDEX));
    }
}
