package com.king.ai.model.dto;

import java.util.List;

public record SupplierTestResult(
        boolean ok,
        String message,
        List<String> models
) {
}
