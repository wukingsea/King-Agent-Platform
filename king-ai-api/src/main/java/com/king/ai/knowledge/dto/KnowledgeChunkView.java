package com.king.ai.knowledge.dto;

public record KnowledgeChunkView(
        Long id,
        Integer chunkIndex,
        Integer charCount,
        String content
) {
}
