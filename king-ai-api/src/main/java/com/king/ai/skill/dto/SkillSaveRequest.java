package com.king.ai.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SkillSaveRequest(
        @NotBlank(message = "技能名称不能为空")
        @Size(max = 100, message = "技能名称不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "技能描述不能超过 500 个字符")
        String description,

        @Size(max = 20, message = "版本号不能超过 20 个字符")
        String version,

        Integer status
) {
}
