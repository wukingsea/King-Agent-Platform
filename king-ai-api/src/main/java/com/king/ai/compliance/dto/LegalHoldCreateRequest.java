package com.king.ai.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record LegalHoldCreateRequest(@NotBlank String resourceType,
                                     @NotNull Long resourceId,
                                     @NotBlank @Size(max = 500) String reason,
                                     LocalDateTime until) {
}
