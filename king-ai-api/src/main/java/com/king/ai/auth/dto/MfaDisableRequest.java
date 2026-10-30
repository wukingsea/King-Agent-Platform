package com.king.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaDisableRequest(
        @NotBlank(message = "请输入当前密码")
        @Size(max = 72, message = "密码过长")
        String password,

        @NotBlank(message = "请输入验证码或恢复码")
        @Size(max = 32, message = "验证码不合法")
        String code
) {
}
