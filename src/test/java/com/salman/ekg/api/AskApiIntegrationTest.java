package com.salman.ekg.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.salman.ekg.agent.ResearchAgent;
import com.salman.ekg.domain.KnowledgeFeedback;
import com.salman.ekg.repository.AgentRunRepository;
import com.salman.ekg.repository.KnowledgeFeedbackRepository;
import com.salman.ekg.support.AbstractIntegrationTest;

@SuppressWarnings("unchecked")
class AskApiIntegrationTest extends AbstractIntegrationTest {

    /** Strings that only exist in the RESTRICTED salary-bands document. */
    private static final List<String> RESTRICTED_MARKERS = List.of("150,000", "185,000", "120,000", "230,000", "Salary Bands");

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private KnowledgeFeedbackRepository feedback;

    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @Test
    @DisplayName("TC-05-03: answer contains the correct fact and cites the right document and section")
    void tc03_answerWithCitations() throws Exception {
        Map<String, Object> res = ask("How many days of annual leave do I get?", EMPLOYEE);

        assertThat(res.get("outcome")).isEqualTo("ANSWERED");
        assertThat(res.get("mode")).isEqualTo("SIMULATED");
        assertThat((String) res.get("answer")).contains("25 days").containsPattern("\\[E\\d+]");
        List<Map<String, Object>> citations = list(res, "citations");
        assertThat(citations).isNotEmpty();
        assertThat(citations).anySatisfy(c -> {
            assertThat(c.get("title")).isEqualTo("Annual Leave Policy");
            assertThat((String) c.get("sectionRef")).contains("Entitlement");
            assertThat(c.get("version")).isEqualTo(1);
        });
        assertThat(list(res, "validationIssues")).isEmpty();
        assertThat(runs.findById(((Number) res.get("answerId")).longValue())).isPresent();
    }

    @Test
    @DisplayName("TC-05-04: no evidence → the assistant abstains and cites nothing")
    void tc04_abstainWithoutEvidence() throws Exception {
        Map<String, Object> res = ask("What is the policy on bringing pets to the office?", EMPLOYEE);
        assertThat(res.get("outcome")).isEqualTo("ABSTAINED");
        assertThat(res.get("answer")).isEqualTo(ResearchAgent.ABSTAIN_MESSAGE);
        assertThat(list(res, "citations")).isEmpty();
        // No SYNTHESIZE step ran: the gate stopped the workflow before any answer generation.
        assertThat(list(res, "trace")).extracting(t -> t.get("phase")).doesNotContain("SYNTHESIZE");
    }

    @Test
    @DisplayName("TC-05-05: a two-part question combines evidence from two documents and cites both")
    void tc05_multiDocumentAnswer() throws Exception {
        Map<String, Object> res = ask("How many annual leave days do I get and how do I reset my VPN token?", EMPLOYEE);
        assertThat(res.get("outcome")).isEqualTo("ANSWERED");
        assertThat(list(res, "subQuestions")).hasSize(2).allSatisfy(s -> assertThat(s.get("supported")).isEqualTo(true));
        String answer = (String) res.get("answer");
        assertThat(answer).contains("25 days").containsIgnoringCase("self-service portal");
        assertThat(list(res, "citations")).extracting(c -> c.get("title"))
                .contains("Annual Leave Policy", "VPN Access Runbook");
    }

    @Test
    @DisplayName("Conflicting sources are reported with both citations, not silently resolved")
    void conflictReported() throws Exception {
        Map<String, Object> res = ask("What is the hotel cap per night?", EMPLOYEE);
        assertThat(res.get("outcome")).isEqualTo("CONFLICT");
        String answer = (String) res.get("answer");
        assertThat(answer).contains("$200").contains("$180").contains("conflict");
        assertThat(list(res, "citations")).extracting(c -> c.get("title"))
                .contains("Travel and Expense Policy", "Business Travel FAQ");
    }

    @Test
    @DisplayName("Partial support: the answered part is cited and the missing part is named")
    void partialAnswer() throws Exception {
        Map<String, Object> res = ask("How do I reset my VPN token and what is the policy on bringing pets to work?", EMPLOYEE);
        assertThat(res.get("outcome")).isEqualTo("PARTIAL");
        assertThat((String) res.get("answer")).containsIgnoringCase("self-service portal")
                .contains("could not find sufficient evidence");
    }

    @Test
    @DisplayName("TC-05-07: an unauthorised user asking about restricted content gets no restricted text anywhere")
    void tc07_noRestrictedLeak() throws Exception {
        String question = "What are the salary bands for L5 engineers?";
        String raw = body(mvc.perform(postJson("/api/knowledge/ask", Map.of("question", question)).with(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ABSTAINED"))
                .andReturn());
        // Not in the answer, citations, evidence or trace: the whole response body is checked.
        RESTRICTED_MARKERS.forEach(marker -> assertThat(raw).doesNotContain(marker));
        assertThat(raw).doesNotContain("RESTRICTED\"");

        // Same question as HR (allowed) is answered from the restricted document - proving the
        // difference comes from the access policy, not from missing data.
        Map<String, Object> hr = ask(question, HR);
        assertThat(hr.get("outcome")).isEqualTo("ANSWERED");
        assertThat((String) hr.get("answer")).contains("$150,000 to $185,000");

        // A different employee cannot reopen HR's stored answer either.
        mvc.perform(get("/api/knowledge/answers/{id}", hr.get("answerId")).with(EMPLOYEE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge/answers/{id}", hr.get("answerId")).with(HR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").value(hr.get("answerId")));
    }

    @Test
    @DisplayName("TC-05-08: prompt-injection text in a retrieved document cannot alter permissions or behaviour")
    void tc08_promptInjectionNeutralised() throws Exception {
        String raw = body(mvc.perform(postJson("/api/knowledge/ask",
                        Map.of("question", "How many days per week can I work remotely?")).with(EMPLOYEE))
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> res = mapper.readValue(raw, Map.class);

        assertThat(res.get("outcome")).isEqualTo("ANSWERED");
        assertThat((String) res.get("answer")).contains("3 days per week");
        // The injected instruction was detected and removed from the evidence...
        List<Map<String, Object>> evidence = list(res, "evidence");
        assertThat(evidence).anySatisfy(e -> assertThat((List<String>) e.get("injectionFlags"))
                .contains("override-instructions"));
        assertThat(evidence).allSatisfy(e -> assertThat((String) e.get("text")).doesNotContainIgnoringCase("ignore all previous instructions"));
        assertThat((List<String>) res.get("warnings")).anySatisfy(w -> assertThat(w).contains("neutralised"));
        // ...no restricted data was revealed and no extra tools were invented
        RESTRICTED_MARKERS.forEach(marker -> assertThat(raw).doesNotContain(marker));
        assertThat(list(res, "trace")).filteredOn(t -> t.get("tool") != null)
                .allSatisfy(t -> assertThat(t.get("tool")).isIn("search_knowledge", "read_chunk"));
        // ...and the caller is still an employee who cannot reach admin endpoints.
        mvc.perform(get("/api/users/pending").with(EMPLOYEE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledge/analytics/summary").with(EMPLOYEE)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-05-11: feedback is persisted and linked to the answer and a cited document")
    void tc11_feedbackPersistedAndLinked() throws Exception {
        Map<String, Object> res = ask("How many days of annual leave do I get?", EMPLOYEE);
        Number answerId = (Number) res.get("answerId");
        Number documentId = (Number) list(res, "citations").getFirst().get("documentId");

        Map<String, Object> body = new HashMap<>();
        body.put("answerId", answerId);
        body.put("documentId", documentId);
        body.put("rating", 4);
        body.put("comment", "Clear and correct");
        mvc.perform(postJson("/api/knowledge/feedback", body).with(EMPLOYEE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerId").value(answerId.intValue()))
                .andExpect(jsonPath("$.documentId").value(documentId.intValue()))
                .andExpect(jsonPath("$.documentTitle").value("Annual Leave Policy"))
                .andExpect(jsonPath("$.rating").value(4));

        List<KnowledgeFeedback> stored = feedback.findByAgentRunId(answerId.longValue());
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getQuestion()).isEqualTo("How many days of annual leave do I get?");
        assertThat(stored.getFirst().getUsername()).isEqualTo("employee");

        // Duplicate → 409; someone else's answer → 404; out-of-range rating → 400; uncited doc → 400
        mvc.perform(postJson("/api/knowledge/feedback", body).with(EMPLOYEE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FEEDBACK_ALREADY_SUBMITTED"));
        mvc.perform(postJson("/api/knowledge/feedback", body).with(HR)).andExpect(status().isNotFound());
        mvc.perform(postJson("/api/knowledge/feedback", Map.of("answerId", answerId, "rating", 6)).with(EMPLOYEE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("rating"));
        Map<String, Object> other = ask("How do I reset my VPN token?", EMPLOYEE);
        mvc.perform(postJson("/api/knowledge/feedback", Map.of("answerId", other.get("answerId"), "documentId", documentId, "rating", 2))
                        .with(EMPLOYEE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOCUMENT_NOT_CITED"));

        mvc.perform(get("/api/knowledge/analytics/feedback-by-document").with(ADMIN))
                .andExpect(jsonPath("$[0].title").value("Annual Leave Policy"))
                .andExpect(jsonPath("$[0].feedback_count").value(1));
    }

    @Test
    @DisplayName("Ask validation and agent status")
    void askValidationAndStatus() throws Exception {
        mvc.perform(postJson("/api/knowledge/ask", Map.of("question", "  ")).with(EMPLOYEE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(postJson("/api/knowledge/ask", Map.of("question", "x".repeat(1001))).with(EMPLOYEE))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/knowledge/agent/status").with(EMPLOYEE))
                .andExpect(jsonPath("$.mode").value("SIMULATED"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.retrievers", Matchers.containsInAnyOrder("KEYWORD", "SEMANTIC")));
    }
}
