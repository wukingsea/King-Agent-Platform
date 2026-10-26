package com.king.ai.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSearchRequest(
        @NotBlank(message = "检索内容不能为空")
        @Size(max = 1000, message = "检索内容不能超过 1000 个字符")
        String query,

        @Min(value = 1, message = "召回数量不能小于 1")
        @Max(value = 20, message = "召回数量不能超过 20")
        Integer topK
) {
}
