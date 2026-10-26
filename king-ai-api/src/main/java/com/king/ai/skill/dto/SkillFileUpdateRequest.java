package com.king.ai.skill.dto;

import jakarta.validation.constraints.Size;

public record SkillFileUpdateRequest(
        @Size(max = 200, message = "文件名称不能超过 200 个字符")
        String name,

        String content,

        Integer sortOrder
) {
}
