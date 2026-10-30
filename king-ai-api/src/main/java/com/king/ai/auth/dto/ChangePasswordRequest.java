package com.king.ai.auth.dto;

import com.king.ai.auth.PasswordRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordRequest(
        @NotBlank(message = "请输入原密码")
        String oldPassword,

        @NotBlank(message = "请输入新密码")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        String newPassword
) {
}
