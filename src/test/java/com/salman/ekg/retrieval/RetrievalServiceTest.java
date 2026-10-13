package com.salman.ekg.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.Role;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.repository.KnowledgeChunkRepository;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.AccessPolicyService;
import com.salman.ekg.support.TestProps;

class RetrievalServiceTest {

    private static final CurrentUser EMPLOYEE = new CurrentUser("employee", Role.EMPLOYEE);

    private final AccessPolicyService policy = mock(AccessPolicyService.class);
    private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        when(policy.allowedLevels(Role.EMPLOYEE)).thenReturn(EnumSet.of(AccessLevel.PUBLIC, AccessLevel.INTERNAL));
        // By default every returned id is live and accessible
        when(chunks.filterLiveAndAccessible(anyCollection(), anyCollection(), anyCollection()))
                .thenAnswer(inv -> List.copyOf((Collection<Long>) inv.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static RetrievedChunk hit(long id, AccessLevel level, String content, double score) {
        return new RetrievedChunk(id, id, "Doc " + id, "HR", level, 1, 0, null, null, content, score);
    }

    private static Retriever retriever(String name, java.util.function.Function<Retriever.Query, List<RetrievedChunk>> fn) {
        return new Retriever() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<RetrievedChunk> retrieve(Query query) {
                return fn.apply(query);
            }
        };
    }

    private RetrievalService service(Retriever... retrievers) {
        return new RetrievalService(List.of(retrievers), policy, chunks, executor, TestProps.defaults());
    }

    @Test
    @DisplayName("TC-05-10: vector store times out → keyword fallback is used and reported")
    void tc10_vectorTimeoutFallsBackToKeyword() {
        Retriever slowVector = retriever("VECTOR", q -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        Retriever keyword = retriever("KEYWORD", q -> List.of(hit(1, AccessLevel.INTERNAL, "annual leave days", 0.5)));

        long start = System.nanoTime();
        RetrievalService.Result result = service(slowVector, keyword).retrieve("annual leave", EMPLOYEE, null, null, 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.mode()).isEqualTo("KEYWORD");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.warnings()).singleElement().asString().contains("VECTOR retriever timed out");
        assertThat(result.hits()).extracting(RetrievedChunk::chunkId).containsExactly(1L);
        assertThat(elapsedMs).as("must not wait for the slow retriever").isLessThan(3_000);
    }

    @Test
    @DisplayName("TC-05-10: a failing retriever falls back; when every retriever fails → 503 RETRIEVAL_UNAVAILABLE")
    void tc10_allRetrieversFail() {
        Retriever broken = retriever("VECTOR", q -> { throw new IllegalStateException("connection refused"); });
        Retriever keyword = retriever("KEYWORD", q -> List.of(hit(1, AccessLevel.PUBLIC, "x", 0.1)));
        assertThat(service(broken, keyword).retrieve("x", EMPLOYEE, null, null, 5).fallbackUsed()).isTrue();

        Retriever keywordDown = retriever("KEYWORD", q -> { throw new IllegalStateException("db down"); });
        assertThatThrownBy(() -> service(broken, keywordDown).retrieve("x", EMPLOYEE, null, null, 5))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("RETRIEVAL_UNAVAILABLE");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("Defence in depth: forbidden or obsolete hits from a misbehaving retriever are dropped")
    void enforcesAccessAndCurrency() {
        Retriever leaky = retriever("VECTOR", q -> List.of(
                hit(1, AccessLevel.RESTRICTED, "salary", 0.9),   // wrong level
                hit(2, AccessLevel.INTERNAL, "salary", 0.8),     // stale (DB says superseded)
                hit(3, AccessLevel.INTERNAL, "salary", 0.7)));
        when(chunks.filterLiveAndAccessible(anyCollection(), anyCollection(), anyCollection())).thenReturn(List.of(3L));

        RetrievalService.Result result = service(leaky).retrieve("salary", EMPLOYEE, null, null, 5);

        assertThat(result.hits()).extracting(RetrievedChunk::chunkId).containsExactly(3L);
    }

    @Test
    @DisplayName("Filtering by an unreadable access level returns nothing without querying")
    void unreadableLevelFilter() {
        Retriever keyword = retriever("KEYWORD", q -> { throw new AssertionError("must not be called"); });
        RetrievalService.Result result = service(keyword).retrieve("salary", EMPLOYEE, null, AccessLevel.RESTRICTED, 5);
        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("Re-rank promotes the passage whose own text covers the question")
    void rerankByCoverage() {
        List<RetrievedChunk> ranked = RetrievalService.rerank("annual leave days", List.of(
                hit(1, AccessLevel.INTERNAL, "Leave requests need 10 working days notice before the day of leave.", 0.9),
                hit(2, AccessLevel.INTERNAL, "Full-time employees receive 25 days of paid annual leave.", 0.4)));
        assertThat(ranked).extracting(RetrievedChunk::chunkId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("Re-rank ignores outer headings that merely repeat the document title (regression)")
    void rerankIgnoresTitleHeading() {
        RetrievedChunk requesting = new RetrievedChunk(3, 1, "Annual Leave Policy", "HR", AccessLevel.INTERNAL, 1, 2,
                "Annual Leave Policy > Requesting Leave", null,
                "Leave requests must be submitted at least 10 working days before the first day of leave.", 0.9);
        RetrievedChunk entitlement = new RetrievedChunk(2, 1, "Annual Leave Policy", "HR", AccessLevel.INTERNAL, 1, 1,
                "Annual Leave Policy > Entitlement", null,
                "Full-time employees receive 25 days of paid annual leave per calendar year.", 0.5);
        assertThat(RetrievalService.rerank("How many annual leave days do I get", List.of(requesting, entitlement)))
                .extracting(RetrievedChunk::chunkId).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("The keyword tsquery is built only from sanitised word tokens")
    void tsQuerySanitised() {
        assertThat(KeywordRetriever.toTsQuery("reset VPN token!")).isEqualTo("reset | vpn | token");
        assertThat(KeywordRetriever.toTsQuery("a & b | !c:* <-> ')")).isEqualTo("");
        assertThat(KeywordRetriever.toTsQuery("what is the")).isEmpty();
    }
}
