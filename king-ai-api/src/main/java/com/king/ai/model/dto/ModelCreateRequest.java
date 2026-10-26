package com.king.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ModelCreateRequest(
        @NotNull(message = "请选择模型供应商")
        Long supplierId,

        @NotBlank(message = "模型名称不能为空")
        @Size(max = 80, message = "模型名称不能超过 80 个字符")
        String name,

        @NotBlank(message = "模型标识不能为空")
        @Size(max = 150, message = "模型标识不能超过 150 个字符")
        String model,

        @NotBlank(message = "模型类型不能为空")
        @Pattern(regexp = "(?i)CHAT|EMBEDDING", message = "模型类型不合法")
        String type,

        Integer dimension,

        Integer status
) {
}
