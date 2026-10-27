package com.king.ai.compliance.dto;

import java.time.LocalDateTime;

public record LegalHoldView(Long id, String resourceType, Long resourceId, String reason,
                            LocalDateTime until, LocalDateTime releasedAt,
                            LocalDateTime createdAt) {
}
