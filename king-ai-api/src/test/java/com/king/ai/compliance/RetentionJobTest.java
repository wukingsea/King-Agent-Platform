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
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionJobTest {

    @Test
    void dryRunDoesNotDeleteOrArchive() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(false);

        f.job.purge(1L, policy("SESSION", true), true, 0L);

        verify(f.storage, never()).store(anyLong(), anyString(), any());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS),
                contains("deleted=1, dryRun=true"), any(), any(), any());
        assertThat(f.registry.counter("king.ai.compliance.retention.deleted",
                "type", "SESSION", "dryRun", "true").count()).isEqualTo(1.0);
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
        when(f.storage.store(eq(1L), eq("gz"), any()))
                .thenReturn(new ExportStorage.StoredFile("1/archive.gz", 10L));

        f.job.purge(1L, policy("SESSION", true), false, 0L);

        verify(f.storage).store(eq(1L), eq("gz"), any());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=0"), any(), any(), any());
    }

    @Test
    void archiveFailureLeavesSessionsUntouched() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)), List.of());
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(false);
        when(f.storage.store(anyLong(), anyString(), any()))
                .thenThrow(new IOException("disk full"));

        f.job.purge(1L, policy("SESSION", true), false, 0L);

        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=0"), any(), any(), any());
        assertThat(f.registry.counter("king.ai.compliance.retention.archive_failed",
                "type", "SESSION").count()).isEqualTo(1.0);
    }

    @Test
    void archiveContainsRedactedMessagesBeforeDelete() throws Exception {
        CapturingStorage storage = new CapturingStorage();
        Fixture f = new Fixture(storage);
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(false);
        when(f.jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(messageRow(), 0));
                });

        f.job.purge(1L, policy("SESSION", true), false, 0L);

        assertThat(storage.extension).isEqualTo("gz");
        String jsonl;
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(storage.bytes.toByteArray()))) {
            jsonl = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(jsonl).contains("\"sessionId\":9").contains("[REDACTED:EMAIL]")
                .doesNotContain("a@b.com");
        verify(f.jdbc).update(contains("DELETE FROM king_ai_chat_session"),
                any(Object[].class));
    }

    @Test
    void sessionDeleteReliesOnCascadeOnly() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(false);

        f.job.purge(1L, policy("SESSION", false), false, 0L);

        verify(f.jdbc, times(1)).update(contains("DELETE FROM king_ai_chat_session"),
                any(Object[].class));
        verify(f.jdbc, never()).update(contains("king_ai_chat_message"), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=1"), any(), any(), any());
    }

    @Test
    void heldMessageIsNotDeleted() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 11L, "session_id", 9L)));
        when(f.holds.isMessageHeld(1L, 11L, 9L)).thenReturn(true);

        f.job.purge(1L, policy("MESSAGE", false), false, 0L);

        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("MESSAGE"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=0"), any(), any(), any());
    }

    @Test
    void unheldMessageIsDeleted() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 11L, "session_id", 9L)));
        when(f.holds.isMessageHeld(1L, 11L, 9L)).thenReturn(false);

        f.job.purge(1L, policy("MESSAGE", false), false, 0L);

        verify(f.jdbc).update(contains("DELETE FROM king_ai_chat_message WHERE id IN"),
                any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("MESSAGE"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=1"), any(), any(), any());
    }

    @Test
    void auditDryRunCountsUnheldRowsOnly() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 5L), Map.of("id", 6L)));
        when(f.holds.isHeld(1L, "AUDIT", 5L)).thenReturn(true);

        f.job.purge(1L, policy("AUDIT", false), true, 0L);

        verify(f.audit, never()).purgeByIds(anyLong(), any());
        verify(f.audit, never()).purgeBefore(anyLong(), any(LocalDateTime.class), any());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("AUDIT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=1"), any(), any(), any());
    }

    @Test
    void auditPurgeDeletesOnlyUnheldIds() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 5L), Map.of("id", 6L)));
        when(f.holds.isHeld(1L, "AUDIT", 5L)).thenReturn(true);
        when(f.audit.purgeByIds(1L, List.of(6L))).thenReturn(1);

        f.job.purge(1L, policy("AUDIT", false), false, 0L);

        verify(f.audit).purgeByIds(1L, List.of(6L));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("AUDIT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=1"), any(), any(), any());
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
    void heldExportIsSkipped() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "file_path", "1/report.csv")));
        when(f.holds.isHeld(1L, "EXPORT", 7L)).thenReturn(true);

        f.job.purge(1L, policy("EXPORT", false), false, 0L);

        verify(f.storage, never()).delete(anyLong(), anyString());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
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

    @Test
    void emptyExportBatchDoesNotSignalTruncation() throws Exception {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of());

        f.job.purge(1L, policy("EXPORT", false), false, 0L);

        assertThat(f.registry.counter("king.ai.compliance.retention.truncated",
                "type", "EXPORT").count()).isEqualTo(0.0);
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("EXPORT"),
                eq(AuditLogService.STATUS_SUCCESS), contains("deleted=0"), any(), any(), any());
    }

    @Test
    void allHeldBatchDoesNotWriteArchive() throws Exception {
        Fixture f = new Fixture();
        f.properties.getCompliance().getRetention().setBatchSize(1);
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(true);

        f.job.purge(1L, policy("SESSION", true), false, 0L);

        verify(f.storage, never()).store(anyLong(), anyString(), any());
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void exhaustedBatchLimitSignalsTruncation() {
        Fixture f = new Fixture();
        f.properties.getCompliance().getRetention().setBatchSize(1);
        f.properties.getCompliance().getRetention().setMaxBatches(1);
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 9L)));
        when(f.holds.isSessionHeld(1L, 9L)).thenReturn(true);

        f.job.purge(1L, policy("SESSION", false), false, 0L);

        assertThat(f.registry.counter("king.ai.compliance.retention.truncated",
                "type", "SESSION").count()).isEqualTo(1.0);
        verify(f.jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void failureIsAuditedAndRethrown() {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> f.job.purge(1L, policy("SESSION", false), false, 0L))
                .isInstanceOf(IllegalStateException.class);

        verify(f.audit).recordEvent(eq(1L), eq(0L), eq("SYSTEM"),
                eq("compliance.retention"), eq("retention"), eq("SESSION"),
                eq(AuditLogService.STATUS_FAILURE), contains("IllegalStateException"),
                any(), any(), any());
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

    private static ResultSet messageRow() throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("role")).thenReturn("user");
        when(rs.getString("content")).thenReturn("联系我 a@b.com");
        when(rs.getString("status")).thenReturn("COMPLETED");
        when(rs.getString("model")).thenReturn("gpt-4o");
        when(rs.getTimestamp("created_at"))
                .thenReturn(Timestamp.valueOf("2026-01-02 03:04:05"));
        return rs;
    }

    private static final class CapturingStorage implements ExportStorage {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private String extension;

        @Override
        public StoredFile store(Long spaceId, String extension, IOConsumer<OutputStream> writer)
                throws IOException {
            this.extension = extension;
            writer.accept(bytes);
            return new StoredFile("1/archive.gz", bytes.size());
        }

        @Override
        public InputStream open(Long spaceId, String relativePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size(Long spaceId, String relativePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long spaceId, String relativePath) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Fixture {

        private final RetentionPolicyService policies = mock(RetentionPolicyService.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalHoldService holds = mock(LegalHoldService.class);
        private final AuditLogService audit = mock(AuditLogService.class);
        private final ExportStorage storage;
        private final KingAiProperties properties = new KingAiProperties();
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final RetentionJob job;

        private Fixture() {
            this(mock(ExportStorage.class));
        }

        private Fixture(ExportStorage storage) {
            this.storage = storage;
            this.job = new RetentionJob(policies, jdbc, holds, audit,
                    properties, storage, new ObjectMapper(), registry,
                    new RedactionService(), mock(ComplianceSettingsService.class));
        }
    }
}
