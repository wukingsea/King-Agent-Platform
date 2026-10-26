package com.king.ai.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeDocTextRequest(
        @NotBlank(message = "文档名称不能为空")
        @Size(max = 200, message = "文档名称不能超过 200 个字符")
        String name,

        @NotBlank(message = "文档内容不能为空")
        @Size(max = 2000000, message = "文档内容不能超过 200 万字符")
        String content
) {
}
