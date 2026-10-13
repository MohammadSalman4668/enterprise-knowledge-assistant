package com.salman.ekg.agent;

import java.util.List;

import com.salman.ekg.agent.AgentTypes.SubQuestion;

/** Decides which tool calls to make to gather evidence. All calls go through {@link AgentToolbox}. */
public interface EvidencePlanner {

    void collect(AgentContext ctx, String question, List<SubQuestion> subQuestions);
}
