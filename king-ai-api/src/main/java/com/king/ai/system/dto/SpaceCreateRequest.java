package com.king.ai.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SpaceCreateRequest(
        @NotBlank(message = "请输入空间名称")
        @Size(max = 50, message = "空间名称过长")
        String name,

        @NotBlank(message = "请输入空间编码")
        @Pattern(regexp = "[a-z0-9][a-z0-9_-]{1,31}", message = "空间编码需为 2-32 位小写字母、数字、下划线或短横线")
        String code,

        @Size(max = 255, message = "空间描述过长")
        String description,

        @NotNull(message = "请选择状态")
        Integer status
) {
}
