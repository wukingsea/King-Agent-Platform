package com.king.ai.knowledge.dto;

import java.time.LocalDateTime;
import java.util.List;

public record KnowledgeDocView(
        Long id,
        Long knowledgeBaseId,
        String name,
        String sourceType,
        Integer chunkCount,
        String status,
        String errorMessage,
        long charCount,
        List<String> allowedRoles,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
