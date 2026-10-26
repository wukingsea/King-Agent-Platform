package com.king.ai.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseSaveRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 100, message = "知识库名称不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "描述不能超过 500 个字符")
        String description,

        @NotNull(message = "请选择向量模型")
        Long embeddingModelId,

        @Min(value = 100, message = "分块大小不能小于 100")
        @Max(value = 6000, message = "分块大小不能超过 6000")
        Integer chunkSize,

        @Min(value = 0, message = "分块重叠不能小于 0")
        @Max(value = 3000, message = "分块重叠不能超过 3000")
        Integer chunkOverlap,

        @Min(value = 1, message = "召回数量不能小于 1")
        @Max(value = 20, message = "召回数量不能超过 20")
        Integer topK,

        Integer status
) {
}
