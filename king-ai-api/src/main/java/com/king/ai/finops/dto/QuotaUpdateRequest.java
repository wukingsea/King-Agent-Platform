package com.king.ai.finops.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record QuotaUpdateRequest(
        @NotNull(message = "请输入每月 Token 配额")
        @Min(value = 0, message = "配额不能为负数")
        Long monthlyTokenQuota
) {
}
