package com.salman.ekg.agent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.salman.ekg.agent.AgentContext;
import com.salman.ekg.agent.AgentToolbox;
import com.salman.ekg.agent.AgentTypes.SubQuestion;
import com.salman.ekg.agent.AgentTypes.ToolResult;
import com.salman.ekg.agent.AgentTypes.ToolSpec;
import com.salman.ekg.agent.EvidencePlanner;

import tools.jackson.databind.ObjectMapper;

/**
 * LIVE planner: the local Ollama model chooses which tool calls to make (function calling).
 * The model only <em>requests</em> a call; {@link AgentToolbox} decides whether it runs,
 * with the caller's real identity, the allow-list, the budget and the injection guard.
 */
public class OllamaEvidencePlanner implements EvidencePlanner {

    private final OllamaClient client;
    private final AgentToolbox toolbox;
    private final ObjectMapper mapper;

    public OllamaEvidencePlanner(OllamaClient client, AgentToolbox toolbox, ObjectMapper mapper) {
        this.client = client;
        this.toolbox = toolbox;
        this.mapper = mapper;
    }

    @Override
    public void collect(AgentContext ctx, String question, List<SubQuestion> subQuestions) {
        StringBuilder prompt = new StringBuilder("Question: ").append(question).append("\nSub-questions:\n");
        subQuestions.forEach(s -> prompt.append("- ").append(s.text()).append('\n'));
        if (ctx.category() != null) {
            prompt.append("The user restricted the search to category: ").append(ctx.category()).append('\n');
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", AgentPrompts.RESEARCH_SYSTEM));
        messages.add(Map.of("role", "user", "content", prompt.toString()));
        List<Map<String, Object>> tools = toolbox.specs().stream().map(OllamaEvidencePlanner::toTool).toList();

        for (int turn = 0; turn <= ctx.maxToolCalls(); turn++) {
            OllamaClient.Message reply = client.chat(messages, tools);
            List<OllamaClient.ToolCall> calls = reply.tool_calls() == null ? List.of() : reply.tool_calls();
            if (calls.isEmpty()) {
                return;
            }
            List<Map<String, Object>> echoed = new ArrayList<>();
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                OllamaClient.ToolCall call = calls.get(i);
                String id = call.id() == null || call.id().isBlank() ? "call_" + turn + "_" + i : call.id();
                String name = call.function() == null ? "" : call.function().name();
                Map<String, Object> args = parseArguments(call.function() == null ? null : call.function().arguments());
                echoed.add(Map.of("id", id, "type", "function",
                        "function", Map.of("name", name == null ? "" : name, "arguments", mapper.writeValueAsString(args))));
                ToolResult r = toolbox.execute(ctx, name, args);
                results.add(Map.of("role", "tool", "tool_call_id", id, "content", r.content()));
            }
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", reply.content() == null ? "" : reply.content());
            assistant.put("tool_calls", echoed);
            messages.add(assistant);
            messages.addAll(results);
            if (ctx.budgetExhausted()) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object raw) {
        try {
            if (raw instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            if (raw instanceof String s && !s.isBlank()) {
                return mapper.readValue(s, Map.class);
            }
        } catch (RuntimeException e) {
            // Malformed arguments are passed on as empty; the toolbox rejects them as INVALID_ARGUMENTS.
        }
        return Map.of();
    }

    static Map<String, Object> toTool(ToolSpec spec) {
        return Map.of("type", "function", "function", Map.of(
                "name", spec.name(),
                "description", spec.description(),
                "parameters", Map.of("type", "object", "properties", spec.properties(), "required", spec.required())));
    }
}
