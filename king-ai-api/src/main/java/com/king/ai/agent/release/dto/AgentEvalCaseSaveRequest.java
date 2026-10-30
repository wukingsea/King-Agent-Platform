package com.king.ai.agent.release.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentEvalCaseSaveRequest(
        @NotBlank(message = "请输入用例名称")
        @Size(max = 120, message = "用例名称不能超过 120 个字符")
        String name,

        @NotBlank(message = "请输入评测问题")
        @Size(max = 10000, message = "评测问题不能超过 10000 个字符")
        String prompt,

        @NotBlank(message = "请输入期望关键词")
        @Size(max = 2000, message = "期望关键词不能超过 2000 个字符")
        String expectedKeywords,

        @Min(value = 1, message = "权重不能小于 1")
        @Max(value = 10, message = "权重不能大于 10")
        Integer weight,

        Integer status
) {
}
