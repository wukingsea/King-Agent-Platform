package com.king.ai.model.dto;

import java.time.LocalDateTime;

public record SupplierView(
        Long id,
        Long spaceId,
        String name,
        String type,
        String baseUrl,
        String apiKeyMasked,
        boolean hasApiKey,
        boolean headersConfigured,
        Integer status,
        String remark,
        long modelCount,
        LocalDateTime createdAt
) {
}
