package com.king.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ModelImportRequest(
        @NotEmpty(message = "请选择要导入的模型")
        @Size(max = 100, message = "一次最多导入 100 个模型")
        List<@NotBlank String> models,

        @Pattern(regexp = "(?i)CHAT|EMBEDDING", message = "模型类型不合法")
        String type,

        Integer dimension
) {
}
