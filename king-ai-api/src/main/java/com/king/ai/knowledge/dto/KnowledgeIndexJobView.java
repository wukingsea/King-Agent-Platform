package com.king.ai.knowledge.dto;

import java.time.LocalDateTime;

public record KnowledgeIndexJobView(
        Long docId,
        String docName,
        Integer attempts,
        String lastError,
        LocalDateTime updatedAt
) {
}
