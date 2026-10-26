package com.king.ai.system.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UserView(
        Long id,
        String username,
        String nickname,
        String email,
        String role,
        Integer status,
        List<Long> spaceIds,
        Integer mustChangePassword,
        LocalDateTime createdAt
) {
}
