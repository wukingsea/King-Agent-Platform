package com.king.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaVerifyRequest(
        @NotBlank(message = "二次验证会话不能为空")
        @Size(max = 500, message = "二次验证会话不合法")
        String challenge,

        @NotBlank(message = "请输入验证码")
        @Size(max = 32, message = "验证码不合法")
        String code
) {
}
