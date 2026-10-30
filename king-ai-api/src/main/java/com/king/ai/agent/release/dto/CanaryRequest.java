package com.king.ai.agent.release.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CanaryRequest(
        @NotNull(message = "请填写灰度比例")
        @Min(value = 1, message = "灰度比例不能小于 1")
        @Max(value = 99, message = "灰度比例不能超过 99，100% 请使用正式发布")
        Integer percent
) {
}
