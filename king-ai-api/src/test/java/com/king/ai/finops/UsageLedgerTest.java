package com.king.ai.finops;

import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import com.king.ai.finops.dto.UsageSummary;
import com.king.ai.finops.entity.UsageRecord;
import com.king.ai.finops.mapper.ModelUsageRow;
import com.king.ai.finops.mapper.UsageLedgerMapper;
import com.king.ai.finops.mapper.UsageTotals;
import com.king.ai.system.entity.Space;
import com.king.ai.system.mapper.SpaceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageLedgerTest {

    @Mock
    private UsageLedgerMapper ledgerMapper;

    @Mock
    private SpaceMapper spaceMapper;

    private KingAiProperties properties;
    private UsageLedger ledger;

    @BeforeEach
    void setUp() {
        properties = new KingAiProperties();
        properties.getFinops().getPrices().put("gpt-4o", price(5.0, 15.0));
        ledger = new UsageLedger(ledgerMapper, spaceMapper, new PriceBook(properties), properties,
                new SimpleMeterRegistry());
    }

    @Test
    void recordStoresTokensCostAndModelSnapshot() {
        ledger.record(7L, 3L, 42L, 99L, "gpt-4o", "ADMIN", 1000, 500, "COMPLETED");

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(ledgerMapper).insert(captor.capture());
        UsageRecord record = captor.getValue();
        assertThat(record.getSpaceId()).isEqualTo(7L);
        assertThat(record.getAgentId()).isEqualTo(3L);
        assertThat(record.getSessionId()).isEqualTo(42L);
        assertThat(record.getMessageId()).isEqualTo(99L);
        assertThat(record.getPromptTokens()).isEqualTo(1000);
        assertThat(record.getCompletionTokens()).isEqualTo(500);
        assertThat(record.getTotalTokens()).isEqualTo(1500);
        assertThat(record.getCostMicros()).isEqualTo(12_500L);
        assertThat(record.getCurrency()).isEqualTo("USD");
        assertThat(record.getStatus()).isEqualTo("COMPLETED");
        assertThat(record.getCreatedAt()).isNotNull();
    }

    @Test
    void recordSkipsRequestsWithoutSpaceOrAgent() {
        ledger.record(null, 3L, 42L, 99L, "gpt-4o", "ADMIN", 1, 1, "COMPLETED");
        ledger.record(7L, null, 42L, 99L, "gpt-4o", "ADMIN", 1, 1, "COMPLETED");

        verifyNoInteractions(ledgerMapper);
    }

    @Test
    void requireQuotaBlocksWhenMonthlyUsageReachedTheLimit() {
        when(spaceMapper.selectById(7L)).thenReturn(space(7L, 1000L));
        when(ledgerMapper.totalsSince(eq(7L), any())).thenReturn(totals(800, 200, 1000, 0));

        assertThatThrownBy(() -> ledger.requireQuota(7L))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(429));
    }

    @Test
    void requireQuotaAllowsUsageBelowTheLimit() {
        when(spaceMapper.selectById(7L)).thenReturn(space(7L, 1000L));
        when(ledgerMapper.totalsSince(eq(7L), any())).thenReturn(totals(100, 100, 200, 0));

        ledger.requireQuota(7L);
    }

    @Test
    void requireQuotaTreatsZeroAsUnlimited() {
        when(spaceMapper.selectById(7L)).thenReturn(space(7L, 0L));

        ledger.requireQuota(7L);

        verify(ledgerMapper, never()).totalsSince(any(), any());
    }

    @Test
    void requireQuotaIsSkippedWhenEnforcementIsDisabled() {
        properties.getFinops().setQuotaEnabled(false);

        ledger.requireQuota(7L);

        verifyNoInteractions(spaceMapper, ledgerMapper);
    }

    @Test
    void summaryReportsRemainingBudgetAndModelBreakdown() {
        when(spaceMapper.selectById(7L)).thenReturn(space(7L, 10_000L));
        when(ledgerMapper.totalsSince(eq(7L), any())).thenReturn(totals(6000, 2000, 8000, 90_000));
        ModelUsageRow row = new ModelUsageRow();
        row.setModel("gpt-4o");
        row.setPromptTokens(6000);
        row.setCompletionTokens(2000);
        row.setTotalTokens(8000);
        row.setCostMicros(90_000);
        when(ledgerMapper.totalsByModelSince(eq(7L), any())).thenReturn(List.of(row));
        UsageRecord latest = new UsageRecord();
        latest.setId(1L);
        latest.setSpaceId(7L);
        latest.setAgentId(3L);
        latest.setPromptTokens(6000);
        latest.setCompletionTokens(2000);
        latest.setTotalTokens(8000);
        latest.setCostMicros(90_000L);
        latest.setStatus("COMPLETED");
        when(ledgerMapper.selectList(any())).thenReturn(List.of(latest));

        UsageSummary summary = ledger.summary(7L);

        assertThat(summary.totalTokens()).isEqualTo(8000);
        assertThat(summary.monthlyTokenQuota()).isEqualTo(10_000);
        assertThat(summary.remainingTokens()).isEqualTo(2_000);
        assertThat(summary.usagePercent()).isEqualTo(80);
        assertThat(summary.models()).hasSize(1);
        assertThat(summary.models().get(0).model()).isEqualTo("gpt-4o");
        assertThat(summary.recent()).hasSize(1);
        assertThat(summary.periodStart().getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void summaryLeavesRemainingEmptyForUnlimitedSpaces() {
        when(spaceMapper.selectById(7L)).thenReturn(space(7L, 0L));
        when(ledgerMapper.totalsSince(eq(7L), any())).thenReturn(totals(10, 10, 20, 0));
        when(ledgerMapper.totalsByModelSince(eq(7L), any())).thenReturn(List.of());
        when(ledgerMapper.selectList(any())).thenReturn(List.of());

        UsageSummary summary = ledger.summary(7L);

        assertThat(summary.remainingTokens()).isNull();
        assertThat(summary.usagePercent()).isNull();
    }

    @Test
    void updateQuotaPersistsAndReturnsTheRefreshedSummary() {
        Space space = space(7L, 0L);
        when(spaceMapper.selectById(7L)).thenReturn(space);
        when(ledgerMapper.totalsSince(eq(7L), any())).thenReturn(totals(0, 0, 0, 0));
        when(ledgerMapper.totalsByModelSince(eq(7L), any())).thenReturn(List.of());
        when(ledgerMapper.selectList(any())).thenReturn(List.of());

        UsageSummary summary = ledger.updateQuota(7L, 500_000L);

        verify(spaceMapper).updateById(space);
        assertThat(space.getMonthlyTokenQuota()).isEqualTo(500_000L);
        assertThat(summary.monthlyTokenQuota()).isEqualTo(500_000L);
        assertThat(summary.remainingTokens()).isEqualTo(500_000L);
    }

    private static Space space(Long id, long quota) {
        Space space = new Space();
        space.setId(id);
        space.setMonthlyTokenQuota(quota);
        return space;
    }

    private static UsageTotals totals(long prompt, long completion, long total, long cost) {
        UsageTotals totals = new UsageTotals();
        totals.setPromptTokens(prompt);
        totals.setCompletionTokens(completion);
        totals.setTotalTokens(total);
        totals.setCostMicros(cost);
        return totals;
    }

    private static KingAiProperties.ModelPrice price(double input, double output) {
        KingAiProperties.ModelPrice price = new KingAiProperties.ModelPrice();
        price.setInputPerMillion(input);
        price.setOutputPerMillion(output);
        return price;
    }
}
