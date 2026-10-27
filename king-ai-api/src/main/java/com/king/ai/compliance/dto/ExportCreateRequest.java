package com.king.ai.compliance.dto;

import java.time.LocalDateTime;

public record ExportCreateRequest(String type, String format, LocalDateTime from, LocalDateTime to) {
}
