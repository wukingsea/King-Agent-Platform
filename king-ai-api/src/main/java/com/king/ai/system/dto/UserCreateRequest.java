package com.king.ai.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserCreateRequest(
        @NotBlank(message = "请输入账号")
        @Pattern(regexp = "[A-Za-z0-9_.-]{3,32}", message = "账号仅支持 3-32 位字母、数字、下划线、点或短横线")
        String username,

        @NotBlank(message = "请输入初始密码")
        @Size(min = 6, max = 72, message = "密码长度需为 6-72 位")
        String password,

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
