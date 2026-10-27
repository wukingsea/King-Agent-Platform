package com.king.ai.compliance.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AccessReviewView(Long id, Long spaceId, String status, String summary,
                               Long generatedBy, LocalDateTime generatedAt,
                               LocalDateTime completedAt, List<AccessReviewItemView> items) {
}
