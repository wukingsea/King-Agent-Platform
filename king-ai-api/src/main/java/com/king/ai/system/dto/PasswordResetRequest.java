package com.king.ai.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "请输入新密码")
        @Size(min = 6, max = 72, message = "密码长度需为 6-72 位")
        String newPassword
) {
}
