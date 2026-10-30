package com.king.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaEnableRequest(
        @NotBlank(message = "请输入验证码")
        @Size(max = 32, message = "验证码不合法")
        String code
) {
}
