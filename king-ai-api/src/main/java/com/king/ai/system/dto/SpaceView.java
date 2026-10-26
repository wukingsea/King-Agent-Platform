package com.king.ai.system.dto;

import java.time.LocalDateTime;

public record SpaceView(
        Long id,
        String name,
        String code,
        String description,
        Integer status,
        String role,
        LocalDateTime createdAt
) {
}
