package com.king.ai.finops.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UsageSummary(
        long spaceId,
        LocalDateTime periodStart,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long costMicros,
        String currency,
        long monthlyTokenQuota,
        Long remainingTokens,
        Integer usagePercent,
        List<ModelUsageView> models,
        List<UsageEntryView> recent
) {

    public record ModelUsageView(
            String model,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long costMicros
    ) {
    }

    public record UsageEntryView(
            Long id,
            Long agentId,
            Long sessionId,
            Long messageId,
            String model,
            String sourceType,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            long costMicros,
            String status,
            LocalDateTime createdAt
    ) {
    }
}
