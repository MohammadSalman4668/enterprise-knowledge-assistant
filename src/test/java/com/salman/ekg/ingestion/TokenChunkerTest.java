package com.salman.ekg.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.support.TestProps;

/** Token-sized chunking: sizes, heading context, overlap, determinism and empty input. */
class TokenChunkerTest {

    private static Chunker chunker(int min, int max, int overlap) {
        KnowledgeProperties d = TestProps.defaults();
        return new Chunker(new KnowledgeProperties(new KnowledgeProperties.Ingestion(5_242_880, min, max, overlap, "approximate"),
                d.retrieval(), d.agent(), d.llm(), d.embedding(), d.seed(), d.admin()));
    }

    private static ParsedDocument md(String text) {
        return new ParsedDocument("text/markdown", List.of(new ParsedDocument.Page(null, text)));
    }

    private static String sentences(String topic, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("Rule ").append(i).append(" about ").append(topic).append(" applies to every team member. ");
        }
        return sb.toString().strip();
    }

    @Test
    @DisplayName("Token counting is documented and reproducible")
    void tokenCounter() {
        TokenCounter approx = TokenCounter.of("approximate");
        assertThat(approx.count("Annual leave: 25 days.")).isEqualTo(6);             // Annual leave : 25 days .
        assertThat(approx.count("internationalisation")).isEqualTo(2);              // long words count twice
        assertThat(TokenCounter.of("whitespace").count("Annual leave: 25 days.")).isEqualTo(4);
        assertThatThrownBy(() -> TokenCounter.of("bpe")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Large sections split at headings; each chunk stays within the maximum and keeps its heading")
    void sectionsAndLimits() {
        Chunker c = chunker(60, 100, 15);
        String text = "# Policy\n\n## Leave\n\n" + sentences("leave", 12) + "\n\n## Travel\n\n" + sentences("travel", 12);
        List<Chunker.ChunkDraft> chunks = c.chunk(md(text));
        assertThat(chunks).allSatisfy(ch -> assertThat(c.countTokens(ch.content())).isLessThanOrEqualTo(100));
        assertThat(chunks).noneMatch(ch -> ch.content().contains("leave") && ch.content().contains("travel"));
        assertThat(chunks.stream().filter(ch -> ch.content().contains("## Travel")).count()).isEqualTo(1);
        assertThat(chunks).filteredOn(ch -> ch.content().contains("travel"))
                .allSatisfy(ch -> assertThat(ch.sectionRef()).isEqualTo("Policy > Travel"));
    }

    @Test
    @DisplayName("Overlap repeats closing sentences only within a section, never across headings")
    void overlapWithinSectionOnly() {
        Chunker c = chunker(60, 100, 20);
        List<Chunker.ChunkDraft> chunks = c.chunk(md("## Leave\n\n" + sentences("leave", 20)));
        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).content();
            String firstSentence = chunks.get(i).content().split("(?<=\\.)\\s")[0];
            assertThat(previous).contains(firstSentence.replace("…", ""));
        }
        List<Chunker.ChunkDraft> twoSections = c.chunk(md("## A\n\n" + sentences("alpha", 8) + "\n\n## B\n\n" + sentences("beta", 8)));
        assertThat(twoSections).filteredOn(ch -> ch.content().contains("## B"))
                .allSatisfy(ch -> assertThat(ch.content()).doesNotContain("alpha"));
    }

    @Test
    @DisplayName("Chunking is deterministic, and every chunk records the configuration that produced it")
    void deterministic() {
        Chunker c = chunker(300, 500, 50);
        String text = "# Guide\n\n" + sentences("security", 60);
        assertThat(c.chunk(md(text))).isEqualTo(c.chunk(md(text)));
        assertThat(c.signature()).isEqualTo("approximate:300-500/50");
    }

    @Test
    @DisplayName("Empty or punctuation-only text yields no chunks (the upload is then rejected as empty)")
    void emptyInput() {
        assertThat(chunker(300, 500, 50).chunk(md("# Only a heading\n\n---\n"))).isEmpty();
        assertThat(chunker(300, 500, 50).chunk(md("   \n\n"))).isEmpty();
    }
}
