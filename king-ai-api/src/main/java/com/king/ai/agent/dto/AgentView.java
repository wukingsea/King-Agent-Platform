package com.king.ai.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AgentView(
        Long id,
        Long spaceId,
        String name,
        String intro,
        Long modelId,
        String modelName,
        String modelIdentifier,
        String systemPrompt,
        BigDecimal temperature,
        Integer maxTokens,
        Integer publishStatus,
        String publicId,
        Integer status,
        Integer publishedVersion,
        List<String> allowedTools,
        String toolApprovalMode,
        Long fallbackModelId,
        List<Long> knowledgeBaseIds,
        List<Long> mcpIds,
        List<Long> skillIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
