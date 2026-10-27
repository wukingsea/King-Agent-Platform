package com.king.ai.compliance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ComplianceSettingUpdateRequest(
        @NotNull Boolean piiEnabled,
        @NotNull String moderationMode,
        @NotNull String inputAction,
        @NotNull String outputAction,
        @Size(max = 20000) String blocklist,
        @Size(max = 8000) String customPiiPatterns
) {
}
