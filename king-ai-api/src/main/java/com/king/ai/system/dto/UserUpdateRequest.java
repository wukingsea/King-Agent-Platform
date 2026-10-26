package com.king.ai.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserUpdateRequest(
        @Size(max = 50, message = "昵称过长")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱过长")
        String email,

        @NotNull(message = "请选择角色")
        @Pattern(regexp = "ADMIN|USER", message = "角色不合法")
        String role,

        @NotNull(message = "请选择状态")
        Integer status,

        List<Long> spaceIds
) {
}
