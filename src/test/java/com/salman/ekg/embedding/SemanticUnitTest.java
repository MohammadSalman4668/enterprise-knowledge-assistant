package com.salman.ekg.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.salman.ekg.agent.AgentTypes.Assessment;
import com.salman.ekg.agent.AgentTypes.EvidenceItem;
import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.agent.AgentTypes.SynthesisInput;
import com.salman.ekg.agent.EvidenceGate;
import com.salman.ekg.agent.ExtractiveSynthesizer;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.retrieval.RetrievedChunk;
import com.salman.ekg.support.TestProps;
import com.salman.ekg.text.TextAnalysis;

class SemanticUnitTest {

    private static EvidenceItem evidence(String ref, long chunkId, String title, String text) {
        RetrievedChunk chunk = new RetrievedChunk(chunkId, chunkId, title, "HR", AccessLevel.INTERNAL, 1, 0, null, null, text, 0.2);
        return new EvidenceItem(ref, chunk, text, List.of());
    }

    /** Scorer with fixed answers, standing in for the embedding model. */
    private static SemanticScorer scorer(Map<Long, Double> chunkSims, List<Double> textSims, double min) {
        return new SemanticScorer() {
            @Override
            public Map<Long, Double> similarityToChunks(String text, Collection<Long> chunkIds) {
                return chunkSims;
            }

            @Override
            public List<Double> similarityToTexts(String text, List<String> candidates) {
                return textSims.subList(0, Math.min(textSims.size(), candidates.size()));
            }

            @Override
            public double minSimilarity() {
                return min;
            }
        };
    }

    @Test
    void normaliseAndDotGiveCosine() {
        float[] a = EmbeddingService.normalize(new float[] {3, 4});
        assertThat(a[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(EmbeddingService.dot(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(EmbeddingService.dot(a, EmbeddingService.normalize(new float[] {-4, 3}))).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(EmbeddingService.dot(a, new float[] {1, 2, 3})).isZero(); // different models/dimensions never match
    }

    @Test
    @DisplayName("Ollama embedding client sends model + inputs and returns vectors in input order")
    void ollamaClientParsesResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/embeddings"))
                .andExpect(content().string(Matchers.containsString("\"model\":\"nomic-embed-text\"")))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(builder.baseUrl("http://localhost:11434/v1").build(), "nomic-embed-text");
        List<float[]> out = client.embed(List.of("first", "second"));
        assertThat(out.get(0)).containsExactly(1f, 0f);
        assertThat(out.get(1)).containsExactly(0f, 1f);
        server.verify();
    }

    @Test
    @DisplayName("Evidence check accepts a passage by meaning when it shares too few words")
    void gateAcceptsByMeaning() {
        String q = "How much paid time off do I get each year";
        SubQuestion sub = new SubQuestion(0, q, TextAnalysis.keyTerms(q));
        EvidenceItem leave = evidence("E1", 11, "Annual Leave Policy", "Full-time employees receive 25 days of annual leave.");
        EvidenceItem vpn = evidence("E2", 22, "VPN Access Runbook", "Reset your VPN token in the portal.");

        Assessment wordsOnly = new EvidenceGate(TestProps.defaults()).assess(List.of(sub), List.of(leave, vpn)).getFirst();
        assertThat(wordsOnly.supported()).as("no shared words → unsupported without meaning").isFalse();

        Assessment withMeaning = new EvidenceGate(TestProps.defaults(), scorer(Map.of(11L, 0.77, 22L, 0.40), List.of(), 0.62))
                .assess(List.of(sub), List.of(leave, vpn)).getFirst();
        assertThat(withMeaning.supported()).isTrue();
        assertThat(withMeaning.bestSimilarity()).isEqualTo(0.77);
        assertThat(withMeaning.support()).extracting(EvidenceItem::ref).containsExactly("E1");
    }

    @Test
    @DisplayName("Extractive fallback quotes the sentence closest in meaning when no words overlap")
    void extractivePicksSentenceByMeaning() {
        String q = "Can I do my job from home";
        SubQuestion sub = new SubQuestion(0, q, TextAnalysis.keyTerms(q));
        EvidenceItem remote = evidence("E1", 7, "Remote Work FAQ",
                "Employees may work remotely up to 3 days per week. Core collaboration hours are 10:00 to 15:00.");
        Assessment part = new Assessment(sub, true, 0.0, 0.66, List.of(remote));
        String answer = new ExtractiveSynthesizer(scorer(Map.of(), List.of(0.71, 0.42), 0.62))
                .compose(new SynthesisInput(q, List.of(part), List.of()));
        assertThat(answer).isEqualTo("Employees may work remotely up to 3 days per week [E1].");
    }
}
