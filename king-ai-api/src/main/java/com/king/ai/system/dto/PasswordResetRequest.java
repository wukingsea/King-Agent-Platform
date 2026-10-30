package com.king.ai.system.dto;

import com.king.ai.auth.PasswordRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetRequest(
        @NotBlank(message = "请输入新密码")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        String newPassword
) {
}
