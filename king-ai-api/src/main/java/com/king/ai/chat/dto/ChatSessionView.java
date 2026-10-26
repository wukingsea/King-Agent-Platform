package com.king.ai.chat.dto;

import java.time.LocalDateTime;

public record ChatSessionView(
        Long id,
        Long agentId,
        Long ownerUserId,
        String agentName,
        String agentPublicId,
        String sourceType,
        String visitorId,
        String title,
        Integer messageCount,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt
) {
}
