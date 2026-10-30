package com.king.ai.agent.dto;

import com.king.ai.agent.entity.AgentVersion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AgentVersionView(
        Integer versionNo,
        String status,
        LocalDateTime publishedAt,
        Long publishedBy,
        Long requestedBy,
        LocalDateTime requestedAt,
        Long approvedBy,
        LocalDateTime approvedAt,
        String reviewComment,
        Integer canaryPercent,
        Integer evalTotal,
        Integer evalPassed,
        BigDecimal evalScore,
        String evalSummary,
        LocalDateTime evalRunAt,
        String configHash,
        boolean current
) {

    public static AgentVersionView of(AgentVersion version, boolean current) {
        LocalDateTime publishedAt = version.getPublishedAt() != null
                ? version.getPublishedAt() : version.getCreatedAt();
        Long publishedBy = version.getApprovedBy() != null
                ? version.getApprovedBy() : version.getCreatedBy();
        return new AgentVersionView(version.getVersionNo(), version.getStatus(), publishedAt, publishedBy,
                version.getRequestedBy(), version.getRequestedAt(), version.getApprovedBy(),
                version.getApprovedAt(), version.getReviewComment(), version.getCanaryPercent(),
                version.getEvalTotal(), version.getEvalPassed(), version.getEvalScore(),
                version.getEvalSummary(), version.getEvalRunAt(), version.getConfigHash(), current);
    }
}
