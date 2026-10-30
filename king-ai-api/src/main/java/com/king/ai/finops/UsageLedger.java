package com.king.ai.finops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import com.king.ai.finops.dto.UsageSummary;
import com.king.ai.finops.entity.UsageRecord;
import com.king.ai.finops.mapper.ModelUsageRow;
import com.king.ai.finops.mapper.UsageLedgerMapper;
import com.king.ai.finops.mapper.UsageTotals;
import com.king.ai.system.entity.Space;
import com.king.ai.system.mapper.SpaceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only LLM usage ledger plus the monthly per-space token budget.
 *
 * <p>The quota check is advisory: two concurrent requests can both pass the check
 * before either records usage. It protects against runaway spend, not against
 * short-lived overruns, and never blocks reads of the ledger.</p>
 */
@Service
@RequiredArgsConstructor
public class UsageLedger {

    private static final int RECENT_LIMIT = 20;

    private final UsageLedgerMapper ledgerMapper;
    private final SpaceMapper spaceMapper;
    private final PriceBook priceBook;
    private final KingAiProperties properties;
    private final MeterRegistry meterRegistry;

    public void record(Long spaceId, Long agentId, Long sessionId, Long messageId, String model,
                       String sourceType, int promptTokens, int completionTokens, String status) {
        if (spaceId == null || agentId == null) {
            return;
        }
        int prompt = Math.max(0, promptTokens);
        int completion = Math.max(0, completionTokens);
        UsageRecord record = new UsageRecord();
        record.setSpaceId(spaceId);
        record.setAgentId(agentId);
        record.setSessionId(sessionId);
        record.setMessageId(messageId);
        record.setModel(model);
        record.setSourceType(sourceType);
        record.setPromptTokens(prompt);
        record.setCompletionTokens(completion);
        record.setTotalTokens(prompt + completion);
        record.setCostMicros(priceBook.costMicros(model, prompt, completion));
        record.setCurrency(priceBook.currency());
        record.setStatus(status);
        record.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(record);
    }

    public long monthlyTokens(Long spaceId) {
        UsageTotals totals = ledgerMapper.totalsSince(spaceId, monthStart());
        return totals == null ? 0 : totals.getTotalTokens();
    }

    public void requireQuota(Long spaceId) {
        if (spaceId == null || !properties.getFinops().isQuotaEnabled()) {
            return;
        }
        long quota = quotaOf(spaceId);
        if (quota <= 0) {
            return;
        }
        if (monthlyTokens(spaceId) >= quota) {
            meterRegistry.counter("king.ai.finops.quota_rejected").increment();
            throw BizException.tooManyRequests("空间本月 Token 配额已用尽，请联系空间管理员调整配额");
        }
    }

    public UsageSummary summary(Long spaceId) {
        Space space = spaceId == null ? null : spaceMapper.selectById(spaceId);
        if (space == null) {
            throw BizException.notFound("业务空间不存在");
        }
        LocalDateTime since = monthStart();
        UsageTotals totals = ledgerMapper.totalsSince(spaceId, since);
        long prompt = totals == null ? 0 : totals.getPromptTokens();
        long completion = totals == null ? 0 : totals.getCompletionTokens();
        long total = totals == null ? 0 : totals.getTotalTokens();
        long cost = totals == null ? 0 : totals.getCostMicros();
        long quota = space.getMonthlyTokenQuota() == null ? 0 : space.getMonthlyTokenQuota();
        Long remaining = quota > 0 ? Math.max(0, quota - total) : null;
        Integer percent = quota > 0 ? (int) Math.min(100, Math.round(total * 100.0 / quota)) : null;
        List<UsageSummary.ModelUsageView> models = ledgerMapper.totalsByModelSince(spaceId, since)
                .stream()
                .map(this::modelView)
                .toList();
        List<UsageSummary.UsageEntryView> recent = ledgerMapper.selectList(
                        new LambdaQueryWrapper<UsageRecord>()
                                .eq(UsageRecord::getSpaceId, spaceId)
                                .orderByDesc(UsageRecord::getId)
                                .last("LIMIT " + RECENT_LIMIT))
                .stream()
                .map(this::entryView)
                .toList();
        return new UsageSummary(spaceId, since, prompt, completion, total, cost,
                priceBook.currency(), quota, remaining, percent, models, recent);
    }

    public UsageSummary updateQuota(Long spaceId, long monthlyTokenQuota) {
        Space space = spaceMapper.selectById(spaceId);
        if (space == null) {
            throw BizException.notFound("业务空间不存在");
        }
        space.setMonthlyTokenQuota(Math.max(0, monthlyTokenQuota));
        spaceMapper.updateById(space);
        return summary(spaceId);
    }

    private long quotaOf(Long spaceId) {
        Space space = spaceMapper.selectById(spaceId);
        return space == null || space.getMonthlyTokenQuota() == null
                ? 0 : space.getMonthlyTokenQuota();
    }

    private UsageSummary.ModelUsageView modelView(ModelUsageRow row) {
        return new UsageSummary.ModelUsageView(row.getModel(), row.getPromptTokens(),
                row.getCompletionTokens(), row.getTotalTokens(), row.getCostMicros());
    }

    private UsageSummary.UsageEntryView entryView(UsageRecord record) {
        return new UsageSummary.UsageEntryView(record.getId(), record.getAgentId(),
                record.getSessionId(), record.getMessageId(), record.getModel(),
                record.getSourceType(), record.getPromptTokens(), record.getCompletionTokens(),
                record.getTotalTokens(), record.getCostMicros(), record.getStatus(),
                record.getCreatedAt());
    }

    private static LocalDateTime monthStart() {
        return LocalDate.now().withDayOfMonth(1).atStartOfDay();
    }
}
