package com.salman.ekg.agent;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.agent.AgentTypes.ToolResult;
import com.salman.ekg.config.KnowledgeProperties;

/**
 * SIMULATED planner (no model): for each sub-question, search, then read the top
 * results. It uses exactly the same toolbox, budget and guards as the live planner, and
 * it is the fallback when the live model fails.
 */
@Component
public class DeterministicEvidencePlanner implements EvidencePlanner {

    private static final Pattern CHUNK_ID = Pattern.compile("^chunk_id=(\\d+)", Pattern.MULTILINE);

    private final AgentToolbox toolbox;
    private final int readsPerSubQuestion;

    public DeterministicEvidencePlanner(AgentToolbox toolbox, KnowledgeProperties props) {
        this.toolbox = toolbox;
        this.readsPerSubQuestion = props.agent().readsPerSubQuestion();
    }

    @Override
    public void collect(AgentContext ctx, String question, List<SubQuestion> subQuestions) {
        for (SubQuestion sub : subQuestions) {
            collectFor(ctx, sub);
        }
    }

    public void collectFor(AgentContext ctx, SubQuestion sub) {
        if (ctx.budgetExhausted()) {
            return;
        }
        ToolResult search = toolbox.execute(ctx, AgentToolbox.SEARCH, Map.of("query", sub.text()));
        if (!search.ok()) {
            return;
        }
        Matcher m = CHUNK_ID.matcher(search.content());
        int reads = 0;
        while (m.find() && reads < readsPerSubQuestion && !ctx.budgetExhausted()) {
            long chunkId = Long.parseLong(m.group(1));
            if (ctx.evidenceFor(chunkId).isEmpty()) {
                toolbox.execute(ctx, AgentToolbox.READ, Map.of("chunk_id", chunkId));
            }
            reads++;
        }
    }
}
