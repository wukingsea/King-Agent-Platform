package com.king.ai.skill.dto;

import java.time.LocalDateTime;

public record SkillView(
        Long id,
        Long spaceId,
        String name,
        String description,
        String version,
        Integer status,
        long fileCount,
        LocalDateTime createdAt
) {
}
