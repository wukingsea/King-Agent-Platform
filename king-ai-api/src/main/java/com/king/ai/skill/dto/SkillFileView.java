package com.king.ai.skill.dto;

import java.time.LocalDateTime;

public record SkillFileView(
        Long id,
        Long parentId,
        String name,
        Integer type,
        String content,
        Integer locked,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
