package com.king.ai.knowledge.dto;

public record KnowledgeSearchResult(
        Long chunkId,
        Long docId,
        String docName,
        Integer chunkIndex,
        String content,
        double score
) {
}
