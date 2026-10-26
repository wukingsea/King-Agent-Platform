package com.king.ai.agent.dto;

import java.time.LocalDateTime;

public record AgentVersionView(
        Integer versionNo,
        LocalDateTime publishedAt,
        Long publishedBy,
        boolean current
) {
}
