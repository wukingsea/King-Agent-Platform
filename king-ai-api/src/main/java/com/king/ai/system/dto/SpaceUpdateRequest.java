package com.king.ai.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SpaceUpdateRequest(
        @NotBlank(message = "请输入空间名称")
        @Size(max = 50, message = "空间名称过长")
        String name,

        @Size(max = 255, message = "空间描述过长")
        String description,

        @NotNull(message = "请选择状态")
        Integer status
) {
}
