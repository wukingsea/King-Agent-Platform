package com.king.ai.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.compliance.retention.RetentionJob;
import com.king.ai.compliance.retention.RetentionPolicy;
import com.king.ai.compliance.retention.RetentionPolicyService;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionJobTest {

    @Test
    void dryRunDoesNotDelete() {
        RetentionPolicyService policies = mock(RetentionPolicyService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalHoldService holds = mock(LegalHoldService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(policies.spacesWithPolicies()).thenReturn(List.of(1L));
        when(policies.enabledPolicies(1L)).thenReturn(List.of(policy("SESSION", true)));
        when(jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L, "activity", LocalDateTime.now())));
        when(holds.isSessionHeld(1L, 9L)).thenReturn(false);
        RetentionJob job = new RetentionJob(policies, jdbc, holds, audit,
                new KingAiProperties(), mock(com.king.ai.compliance.export.ExportStorage.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), new SimpleMeterRegistry(),
                new RedactionService(), mock(ComplianceSettingsService.class));

        job.purge(1L, policy("SESSION", true), true, 0L);

        verify(jdbc, never()).update(any(String.class), any(Object[].class));
        verify(audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), any(), any(), any(), any());
    }

    @Test
    void heldSessionIsNotDeleted() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(true);

        f.job.purge(1L, policy("SESSION", false), false, 0L);

        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=0"), any(), any(), any());
    }

    @Test
    void sessionHeldDuringArchiveIsNotDeleted() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(false, true);
        when(f.storage.store(eq(1L), eq("jsonl.gz"), any()))
                .thenReturn(new ExportStorage.StoredFile("1/archive.jsonlgz", 10L));

        f.job.purge(1L, policy("SESSION", true), false, 0L);

        verify(f.storage).store(eq(1L), eq("jsonl.gz"), any());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=0"), any(), any(), any());
    }

    @Test
    void auditDryRunCountExcludesHeldIds() {
        Fixture f = new Fixture();
        when(f.holds.activeResourceIds(1L, "AUDIT")).thenReturn(Set.of(5L));
        when(f.jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);

        f.job.purge(1L, policy("AUDIT", false), true, 0L);

        verify(f.audit, never()).purgeBefore(anyLong(), any(LocalDateTime.class), any());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("AUDIT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=3"), any(), any(), any());
    }

    @Test
    void auditPurgePassesHeldIds() {
        Fixture f = new Fixture();
        when(f.holds.activeResourceIds(1L, "AUDIT")).thenReturn(Set.of(5L));
        when(f.audit.purgeBefore(eq(1L), any(LocalDateTime.class), eq(Set.of(5L))))
                .thenReturn(4);

        f.job.purge(1L, policy("AUDIT", false), false, 0L);

        verify(f.audit).purgeBefore(eq(1L), any(LocalDateTime.class), eq(Set.of(5L)));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("AUDIT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=4"), any(), any(), any());
    }

    @Test
    void exportDryRunTouchesNoFiles() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "file_path", "1/report.csv")));

        f.job.purge(1L, policy("EXPORT", false), true, 0L);

        verify(f.storage, never()).delete(anyLong(), anyString());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("EXPORT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=1"), any(), any(), any());
    }

    @Test
    void exportPurgeDeletesFileBeforeRowUpdate() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "file_path", "1/report.csv")));

        f.job.purge(1L, policy("EXPORT", false), false, 0L);

        InOrder order = inOrder(f.storage, f.jdbc);
        order.verify(f.storage).delete(1L, "1/report.csv");
        order.verify(f.jdbc).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("EXPORT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=1"), any(), any(), any());
    }

    private static RetentionPolicy policy(String type, boolean archive) {
        RetentionPolicy policy = new RetentionPolicy();
        policy.setSpaceId(1L);
        policy.setResourceType(type);
        policy.setRetentionDays(30);
        policy.setEnabled(1);
        policy.setArchiveBeforeDelete(archive ? 1 : 0);
        return policy;
    }

    private static final class Fixture {

        private final RetentionPolicyService policies = mock(RetentionPolicyService.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalHoldService holds = mock(LegalHoldService.class);
        private final AuditLogService audit = mock(AuditLogService.class);
        private final ExportStorage storage = mock(ExportStorage.class);
        private final RetentionJob job = new RetentionJob(policies, jdbc, holds, audit,
                new KingAiProperties(), storage, new ObjectMapper(), new SimpleMeterRegistry(),
                new RedactionService(), mock(ComplianceSettingsService.class));
    }
}
