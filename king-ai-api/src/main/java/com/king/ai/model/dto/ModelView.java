package com.king.ai.model.dto;

import java.time.LocalDateTime;

public record ModelView(
        Long id,
        Long spaceId,
        Long supplierId,
        String supplierName,
        String name,
        String model,
        String type,
        Integer dimension,
        Integer status,
        long usageCount,
        LocalDateTime createdAt
) {
}
