package com.king.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordRequest(
        @NotBlank(message = "请输入原密码")
        String oldPassword,

        @NotBlank(message = "请输入新密码")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$",
                message = "新密码需为 8-72 位，且同时包含字母和数字")
        String newPassword
) {
}
