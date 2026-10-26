package com.king.ai.knowledge.dto;

import java.time.LocalDateTime;

public record KnowledgeDocDetail(
        Long id,
        Long knowledgeBaseId,
        String name,
        String sourceType,
        String content,
        Integer chunkCount,
        String status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
