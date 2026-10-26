package com.king.ai.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record ChatMessageView(
        Long id,
        Long sessionId,
        String role,
        String content,
        String reasoning,
        JsonNode toolTrace,
        JsonNode citations,
        String status,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        LocalDateTime createdAt
) {
}
