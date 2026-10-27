package com.king.ai.compliance.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AccessReviewItemView(Long id, String subjectType, Long subjectId,
                                   String subjectName, String role,
                                   LocalDateTime lastActivityAt, List<String> flags,
                                   String decision, Long decidedBy, LocalDateTime decidedAt,
                                   String note) {
}
