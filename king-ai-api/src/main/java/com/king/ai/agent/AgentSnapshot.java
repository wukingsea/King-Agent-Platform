package com.king.ai.agent;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable view of everything that affects an agent's runtime behavior.
 * Published chats execute from this snapshot rather than from the editable agent row.
 */
public record AgentSnapshot(
        String name,
        String intro,
        Long modelId,
        String systemPrompt,
        BigDecimal temperature,
        Integer maxTokens,
        String allowedTools,
        String toolApprovalMode,
        Long fallbackModelId,
        List<Long> knowledgeBaseIds,
        List<Long> mcpIds,
        List<Long> skillIds
) {
}
