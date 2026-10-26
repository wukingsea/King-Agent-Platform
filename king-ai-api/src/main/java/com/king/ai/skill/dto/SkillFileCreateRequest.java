package com.king.ai.skill.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SkillFileCreateRequest(
        Long parentId,

        @NotBlank(message = "文件名称不能为空")
        @Size(max = 200, message = "文件名称不能超过 200 个字符")
        String name,

        @Min(value = 0, message = "文件类型不合法")
        @Max(value = 1, message = "文件类型不合法")
        Integer type,

        String content,

        Integer sortOrder
) {
}
