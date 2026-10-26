package com.king.ai.model.dto;

public record ModelOption(
        Long id,
        String name,
        String model,
        String type,
        Integer dimension,
        String supplierName
) {
}
