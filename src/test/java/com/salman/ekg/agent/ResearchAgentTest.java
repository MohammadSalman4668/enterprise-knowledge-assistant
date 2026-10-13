package com.salman.ekg.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AgentMode;
import com.salman.ekg.domain.AnswerOutcome;
import com.salman.ekg.domain.Role;
import com.salman.ekg.dto.AskDtos.AskRequest;
import com.salman.ekg.dto.AskDtos.AskResponse;
import com.salman.ekg.retrieval.RetrievalService;
import com.salman.ekg.retrieval.RetrievedChunk;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.SearchService;
import com.salman.ekg.support.TestProps;

/** Unit tests of the agent workflow with the retrieval layer mocked and the model replaced by test doubles. */
class ResearchAgentTest {

    private static final CurrentUser EMPLOYEE = new CurrentUser("employee", Role.EMPLOYEE);

    private static final RetrievedChunk LEAVE = new RetrievedChunk(11, 1, "Annual Leave Policy", "HR", AccessLevel.INTERNAL,
            1, 0, "Annual Leave Policy > Entitlement", null,
            "Full-time employees receive 25 days of paid annual leave per calendar year.", 0.5);
    private static final RetrievedChunk VPN = new RetrievedChunk(21, 2, "VPN Access Runbook", "IT", AccessLevel.INTERNAL,
            1, 1, "VPN Access Runbook > Resetting a VPN Token", null,
            "To reset your VPN token, open the self-service portal and confirm with the Authenticator app.", 0.4);

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final SearchService searchService = mock(SearchService.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private ExecutorService executor;
    private KnowledgeProperties props;
    private AgentToolbox toolbox;
    private DeterministicEvidencePlanner deterministic;
    private ExtractiveSynthesizer extractive;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        props = TestProps.withTimeouts(Duration.ofSeconds(1), Duration.ofMillis(200));
        toolbox = new AgentToolbox(retrieval, searchService, new InjectionGuard());
        deterministic = new DeterministicEvidencePlanner(toolbox, props);
        extractive = new ExtractiveSynthesizer();
        when(runs.save(any(), any())).thenReturn(42L);
        when(retrieval.retrieve(anyString(), any(), any(), any(), anyInt())).thenAnswer(inv -> {
            String q = ((String) inv.getArgument(0)).toLowerCase();
            List<RetrievedChunk> hits = q.contains("vpn") && !q.contains("leave") ? List.of(VPN)
                    : q.contains("leave") ? List.of(LEAVE) : List.of();
            return new RetrievalService.Result(hits, "KEYWORD", false, List.of());
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ResearchAgent agent(AgentModels models) {
        return new ResearchAgent(new QueryPlanner(), models, deterministic, extractive, new EvidenceGate(props),
                new ConflictDetector(), new CitationValidator(), runs, executor, props, Clock.systemUTC());
    }

    private ResearchAgent simulated() {
        return agent(new AgentModels(AgentMode.SIMULATED, "sim", deterministic, extractive));
    }

    @Test
    @DisplayName("TC-05-03 (unit): supported question is answered with the fact and a citation")
    void answersWithCitation() {
        AskResponse res = simulated().ask(new AskRequest("How many days of annual leave do I get?", null), EMPLOYEE);
        assertThat(res.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(res.answer()).contains("25 days").contains("[E1]");
        assertThat(res.citations()).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("Annual Leave Policy");
            assertThat(c.sectionRef()).contains("Entitlement");
        });
        assertThat(res.answerId()).isEqualTo(42L);
        assertThat(res.degraded()).isFalse();
    }

    @Test
    @DisplayName("TC-05-04 (unit): no evidence → abstain without ever calling the synthesizer")
    void abstainsWithoutCallingModel() {
        AnswerSynthesizer model = mock(AnswerSynthesizer.class);
        AskResponse res = agent(new AgentModels(AgentMode.LIVE, "m", deterministic, model))
                .ask(new AskRequest("What is the policy on bringing pets to the office?", null), EMPLOYEE);
        assertThat(res.outcome()).isEqualTo(AnswerOutcome.ABSTAINED);
        assertThat(res.answer()).isEqualTo(ResearchAgent.ABSTAIN_MESSAGE);
        assertThat(res.citations()).isEmpty();
        verifyNoInteractions(model);
    }

    @Test
    @DisplayName("TC-05-05 (unit): two goals, two documents, both cited")
    void combinesTwoDocuments() {
        AskResponse res = simulated().ask(
                new AskRequest("How many annual leave days do I get and how do I reset my VPN token?", null), EMPLOYEE);
        assertThat(res.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(res.subQuestions()).hasSize(2);
        assertThat(res.citations()).extracting(c -> c.title()).containsExactlyInAnyOrder("Annual Leave Policy", "VPN Access Runbook");
    }

    @Test
    @DisplayName("TC-05-10 (unit): model times out → extractive fallback, marked degraded")
    void modelTimeoutFallsBack() {
        AnswerSynthesizer slowModel = input -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never";
        };
        long start = System.nanoTime();
        AskResponse res = agent(new AgentModels(AgentMode.LIVE, "m", deterministic, slowModel))
                .ask(new AskRequest("How many days of annual leave do I get?", null), EMPLOYEE);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(res.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(res.degraded()).isTrue();
        assertThat(res.answer()).contains("25 days");
        assertThat(res.warnings()).anySatisfy(w -> assertThat(w).contains("did not respond in time"));
        assertThat(res.trace()).anySatisfy(t -> {
            assertThat(t.phase()).isEqualTo("SYNTHESIZE");
            assertThat(t.status()).isEqualTo("DEGRADED");
        });
        assertThat(ms).as("bounded by the synthesis timeout").isLessThan(4_500);
    }

    @Test
    @DisplayName("TC-05-10 (unit): model planner fails → deterministic research, still answered")
    void plannerFailureFallsBack() {
        EvidencePlanner failingModel = (ctx, q, subs) -> { throw new IllegalStateException("model HTTP 529 overloaded"); };
        AskResponse res = agent(new AgentModels(AgentMode.LIVE, "m", failingModel, extractive))
                .ask(new AskRequest("How do I reset my VPN token?", null), EMPLOYEE);
        assertThat(res.outcome()).isEqualTo(AnswerOutcome.ANSWERED);
        assertThat(res.degraded()).isTrue();
        assertThat(res.answer()).containsIgnoringCase("self-service portal");
    }

    @Test
    @DisplayName("TC-05-08 (unit): a model following injected instructions cannot escalate or reach other chunks")
    void maliciousModelIsContained() {
        EvidencePlanner compromised = (ctx, q, subs) -> {
            toolbox.execute(ctx, "grant_access", Map.of("role", "ADMIN"));
            toolbox.execute(ctx, "read_chunk", Map.of("chunk_id", 999));           // a restricted chunk id it guessed
            toolbox.execute(ctx, "search_knowledge", Map.of("query", "salary bands", "role", "ADMIN"));
        };
        AskResponse res = agent(new AgentModels(AgentMode.LIVE, "m", compromised, extractive))
                .ask(new AskRequest("What are the salary bands?", null), EMPLOYEE);

        assertThat(res.trace()).filteredOn(t -> "grant_access".equals(t.tool()))
                .singleElement().satisfies(t -> assertThat(t.status()).isEqualTo("DENIED"));
        assertThat(res.trace()).filteredOn(t -> "read_chunk".equals(t.tool()) && t.input().get("chunk_id").equals(999))
                .singleElement().satisfies(t -> assertThat(t.status()).isEqualTo("DENIED"));
        // The search ran with the real caller identity, whatever the model put in its arguments
        verify(retrieval).retrieve(eq("salary bands"), eq(EMPLOYEE), any(), any(), anyInt());
        verify(retrieval, never()).retrieve(anyString(), eq(new CurrentUser("employee", Role.ADMIN)), any(), any(), anyInt());
        assertThat(res.outcome()).isEqualTo(AnswerOutcome.ABSTAINED);
    }

    @Test
    @DisplayName("Hallucinated figures and unknown citations are removed; extractive answer replaces an invalid model answer")
    void invalidModelOutputIsCorrected() {
        AnswerSynthesizer hallucinating = input ->
                "Employees get 40 days of annual leave [E1]. Every employee also gets a company car [E9].";
        AskResponse res = agent(new AgentModels(AgentMode.LIVE, "m", deterministic, hallucinating))
                .ask(new AskRequest("How many days of annual leave do I get?", null), EMPLOYEE);
        assertThat(res.answer()).contains("25 days").doesNotContain("40 days").doesNotContain("car");
        assertThat(res.validationIssues()).anySatisfy(i -> assertThat(i).startsWith("UNSUPPORTED_NUMBER_REMOVED"));
        assertThat(res.validationIssues()).anySatisfy(i -> assertThat(i).startsWith("UNKNOWN_CITATION_REMOVED"));
        assertThat(res.degraded()).isTrue();
    }

    @Test
    @DisplayName("Tool budget is a hard limit")
    void toolBudgetEnforced() {
        AgentContext ctx = new AgentContext(EMPLOYEE, null, 2);
        toolbox.execute(ctx, "search_knowledge", Map.of("query", "leave"));
        toolbox.execute(ctx, "search_knowledge", Map.of("query", "leave"));
        AgentTypes.ToolResult third = toolbox.execute(ctx, "search_knowledge", Map.of("query", "leave"));
        assertThat(third.ok()).isFalse();
        assertThat(third.content()).contains("budget");
        assertThat(ctx.trace()).last().satisfies(t -> assertThat(t.status()).isEqualTo("DENIED"));
    }
}
