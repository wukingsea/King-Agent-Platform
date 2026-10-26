package com.king.ai.agent;

import java.util.List;

public record AgentBindings(
        List<Long> knowledgeBaseIds,
        List<Long> mcpIds,
        List<Long> skillIds
) {

    public static AgentBindings empty() {
        return new AgentBindings(List.of(), List.of(), List.of());
    }
}
