package com.king.ai.knowledge.dto;

import java.time.LocalDateTime;

public record KnowledgeBaseView(
        Long id,
        Long spaceId,
        String name,
        String description,
        Long embeddingModelId,
        String embeddingModelName,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer topK,
        String vectorCollection,
        Integer embeddingDim,
        long docCount,
        long chunkCount,
        Integer status,
        LocalDateTime createdAt
) {
}
