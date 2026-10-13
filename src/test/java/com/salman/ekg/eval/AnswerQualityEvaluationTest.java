package com.salman.ekg.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.salman.ekg.support.AbstractIntegrationTest;

import tools.jackson.core.type.TypeReference;

/**
 * TC-05-12: evaluates the assistant on a fixed question set. It scores <em>grounding</em>,
 * not wording:
 * <ul>
 *   <li><b>outcome accuracy</b>: answered / abstained / conflict as expected;</li>
 *   <li><b>fact support</b>: each expected fact appears in the answer <em>and</em> in a cited passage;</li>
 *   <li><b>citation recall</b>: the expected source documents are cited;</li>
 *   <li><b>citation precision</b>: cited documents are expected ones (no padding with irrelevant sources).</li>
 * </ul>
 * A report is written to target/eval-report.md.
 */
@SuppressWarnings("unchecked")
class AnswerQualityEvaluationTest extends AbstractIntegrationTest {

    record Case(String id, String role, String question, String expectedOutcome, List<String> facts, List<String> documents) {}

    record Score(Case c, String outcome, boolean outcomeOk, double factSupport, double recall, double precision) {}

    @Test
    @DisplayName("TC-05-12: fixed question set meets grounding and citation thresholds")
    void evaluateQuestionSet() throws Exception {
        List<Case> cases;
        try (InputStream in = new ClassPathResource("eval/questions.json").getInputStream()) {
            cases = mapper.readValue(in, new TypeReference<List<Case>>() { });
        }
        List<Score> scores = new ArrayList<>();
        for (Case c : cases) {
            scores.add(score(c, ask(c.question(), as(c.role()))));
        }
        writeReport(scores);

        double outcomeAccuracy = scores.stream().filter(Score::outcomeOk).count() / (double) scores.size();
        List<Score> withFacts = scores.stream().filter(s -> !s.c().facts().isEmpty()).toList();
        double factSupport = withFacts.stream().mapToDouble(Score::factSupport).average().orElse(1);
        double recall = withFacts.stream().mapToDouble(Score::recall).average().orElse(1);
        double precision = withFacts.stream().mapToDouble(Score::precision).average().orElse(1);

        assertThat(outcomeAccuracy).as("outcome accuracy").isGreaterThanOrEqualTo(0.9);
        assertThat(factSupport).as("mean fact support").isGreaterThanOrEqualTo(0.9);
        assertThat(recall).as("mean citation recall").isGreaterThanOrEqualTo(0.9);
        assertThat(precision).as("mean citation precision").isGreaterThanOrEqualTo(0.8);
        // Abstention cases must never cite anything
        scores.stream().filter(s -> "ABSTAINED".equals(s.c().expectedOutcome()))
                .forEach(s -> assertThat(s.outcome()).as(s.c().id()).isEqualTo("ABSTAINED"));
    }

    private Score score(Case c, Map<String, Object> res) {
        String outcome = (String) res.get("outcome");
        String answer = ((String) res.get("answer")).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) res.get("evidence");
        StringBuilder citedText = new StringBuilder();
        evidence.stream().filter(e -> Boolean.TRUE.equals(e.get("cited")))
                .forEach(e -> citedText.append(((String) e.get("text")).toLowerCase(Locale.ROOT)).append('\n'));
        long supported = c.facts().stream()
                .map(f -> f.toLowerCase(Locale.ROOT))
                .filter(f -> answer.contains(f) && citedText.toString().contains(f))
                .count();
        double factSupport = c.facts().isEmpty() ? 1 : supported / (double) c.facts().size();

        Set<String> cited = new HashSet<>();
        ((List<Map<String, Object>>) res.get("citations")).forEach(ci -> cited.add((String) ci.get("title")));
        Set<String> expected = new HashSet<>(c.documents());
        long hits = cited.stream().filter(expected::contains).count();
        double recall = expected.isEmpty() ? (cited.isEmpty() ? 1 : 0) : hits / (double) expected.size();
        double precision = cited.isEmpty() ? (expected.isEmpty() ? 1 : 0) : hits / (double) cited.size();
        return new Score(c, outcome, c.expectedOutcome().equals(outcome), factSupport, recall, precision);
    }

    private void writeReport(List<Score> scores) throws Exception {
        StringBuilder md = new StringBuilder("# Answer quality evaluation (TC-05-12)\n\n")
                .append("| ID | Role | Question | Expected | Actual | Fact support | Citation recall | Citation precision |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Score s : scores) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %.2f | %.2f | %.2f |%n", s.c().id(), s.c().role(),
                    s.c().question(), s.c().expectedOutcome(), s.outcome() + (s.outcomeOk() ? "" : " ✗"),
                    s.factSupport(), s.recall(), s.precision()));
        }
        Path out = Path.of("target", "eval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }
}
