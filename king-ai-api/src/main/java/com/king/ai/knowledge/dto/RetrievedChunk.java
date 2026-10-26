package com.king.ai.knowledge.dto;

public record RetrievedChunk(
        Long chunkId,
        Long docId,
        String docName,
        Long knowledgeBaseId,
        String knowledgeBaseName,
        Integer chunkIndex,
        String content,
        double score
) {
}
