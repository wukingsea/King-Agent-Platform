package com.king.ai.compliance.dto;

import java.time.LocalDateTime;

public record ExportJobView(Long id, String exportType, String format, String status,
                            String fileName, long rowCount, String errorMessage, Long requestedBy,
                            LocalDateTime fromAt, LocalDateTime toAt, LocalDateTime expiresAt,
                            LocalDateTime createdAt, LocalDateTime completedAt) {
}
