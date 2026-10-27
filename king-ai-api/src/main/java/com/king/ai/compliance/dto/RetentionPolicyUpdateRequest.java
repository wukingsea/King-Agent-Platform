package com.king.ai.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RetentionPolicyUpdateRequest(@NotBlank String resourceType,
                                           @NotNull Integer retentionDays,
                                           Boolean enabled, Boolean archiveBeforeDelete) {
}
