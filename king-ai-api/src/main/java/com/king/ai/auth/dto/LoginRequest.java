package com.king.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "请输入账号")
        @Size(max = 50, message = "账号过长")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(max = 72, message = "密码过长")
        String password
) {
}
