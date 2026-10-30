package com.king.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaSetupRequest(
        @NotBlank(message = "请输入当前密码")
        @Size(max = 72, message = "密码过长")
        String password
) {
}
