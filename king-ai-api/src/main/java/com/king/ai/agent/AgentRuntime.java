package com.king.ai.agent;

import com.king.ai.agent.entity.Agent;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resolved agent configuration used by a single chat request.
 * Admin chats use the live row; public chats use the frozen published snapshot.
 */
public record AgentRuntime(
        Long agentId,
        Long spaceId,
        String publicId,
        String name,
        String intro,
        Long modelId,
        String systemPrompt,
        BigDecimal temperature,
        Integer maxTokens,
        Integer status,
        String allowedTools,
        String toolApprovalMode,
        Long fallbackModelId,
        AgentBindings bindings
) {

    public static AgentRuntime live(Agent agent, AgentBindings bindings) {
        return new AgentRuntime(agent.getId(), agent.getSpaceId(), agent.getPublicId(), agent.getName(),
                agent.getIntro(), agent.getModelId(), agent.getSystemPrompt(), agent.getTemperature(),
                agent.getMaxTokens(), agent.getStatus(), agent.getAllowedTools(), agent.getToolApprovalMode(),
                agent.getFallbackModelId(), bindings);
    }

    public static AgentRuntime published(Agent agent, AgentSnapshot snapshot) {
        return new AgentRuntime(agent.getId(), agent.getSpaceId(), agent.getPublicId(), snapshot.name(),
                snapshot.intro(), snapshot.modelId(), snapshot.systemPrompt(), snapshot.temperature(),
                snapshot.maxTokens(), 0, snapshot.allowedTools(), snapshot.toolApprovalMode(),
                snapshot.fallbackModelId(),
                new AgentBindings(nullSafe(snapshot.knowledgeBaseIds()), nullSafe(snapshot.mcpIds()),
                        nullSafe(snapshot.skillIds())));
    }

    private static List<Long> nullSafe(List<Long> values) {
        return values == null ? List.of() : values;
    }
}
