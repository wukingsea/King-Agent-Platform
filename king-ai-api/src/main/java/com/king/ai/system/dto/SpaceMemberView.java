package com.king.ai.system.dto;

public record SpaceMemberView(
        Long userId,
        String username,
        String nickname,
        String role
) {
}
