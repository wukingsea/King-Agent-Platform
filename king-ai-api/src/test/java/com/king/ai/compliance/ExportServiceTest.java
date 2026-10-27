package com.king.ai.compliance;

import com.king.ai.compliance.dto.ExportCreateRequest;
import com.king.ai.compliance.export.ExportService;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.compliance.retention.ExportJob;
import com.king.ai.compliance.retention.ExportJobMapper;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportServiceTest {

    private final ExportJobMapper mapper = mock(ExportJobMapper.class);
    private final ExportStorage storage = mock(ExportStorage.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LegalHoldService holds = mock(LegalHoldService.class);
    private final ExportService service = new ExportService(mapper, storage, jdbc,
            new ObjectMapper(), holds, new com.king.ai.config.KingAiProperties(),
            mock(com.king.ai.audit.AuditLogService.class), new RedactionService(),
            mock(ComplianceSettingsService.class));

    @Test
    void rejectsInvalidExportType() {
        assertThatThrownBy(() -> service.create(1L,
                new ExportCreateRequest("UNKNOWN", "JSONL", null, null), 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导出类型");
    }

    @Test
    void rejectsInvertedDateRange() {
        assertThatThrownBy(() -> service.create(1L,
                new ExportCreateRequest("CONVERSATIONS", "CSV",
                        java.time.LocalDateTime.now(), java.time.LocalDateTime.now().minusDays(1)), 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("时间范围");
    }

    @Test
    void downloadRejectsMissingOrForeignJob() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.openDownload(1L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导出任务不存在");
    }

    @Test
    void downloadRejectsCompletedJobWithoutFile() {
        ExportJob job = new ExportJob();
        job.setId(9L);
        job.setSpaceId(1L);
        job.setStatus("COMPLETED");
        job.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(mapper.selectById(9L)).thenReturn(job);

        assertThatThrownBy(() -> service.openDownload(1L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导出文件不存在");
    }

    @Test
    void downloadRejectsCompletedJobOfAnotherSpace() {
        ExportJob job = new ExportJob();
        job.setId(9L);
        job.setSpaceId(2L);
        job.setStatus("COMPLETED");
        job.setFilePath("2/export.jsonl");
        job.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(mapper.selectById(9L)).thenReturn(job);

        assertThatThrownBy(() -> service.openDownload(1L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导出任务不存在");
    }

    @Test
    void downloadAllowsExpiredJobUnderLegalHold() throws Exception {
        ExportJob job = new ExportJob();
        job.setId(9L);
        job.setSpaceId(1L);
        job.setStatus("COMPLETED");
        job.setFileName("king-ai-conversations-9.jsonl");
        job.setFilePath("1/export.jsonl");
        job.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(mapper.selectById(9L)).thenReturn(job);
        when(holds.isHeld(1L, "EXPORT", 9L)).thenReturn(true);
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(storage.size(1L, "1/export.jsonl")).thenReturn(3L);
        when(storage.open(1L, "1/export.jsonl")).thenReturn(stream);

        ExportService.Download download = service.openDownload(1L, 9L);

        assertThat(download.fileName()).isEqualTo("king-ai-conversations-9.jsonl");
        assertThat(download.size()).isEqualTo(3L);
        assertThat(download.stream()).isSameAs(stream);
    }

    @Test
    void deleteFileRejectsHeldCompletedJob() throws Exception {
        ExportJob job = new ExportJob();
        job.setId(9L);
        job.setSpaceId(1L);
        job.setStatus("COMPLETED");
        job.setFilePath("1/export.jsonl");
        when(holds.isHeld(1L, "EXPORT", 9L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteFile(job))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("法律保留");
        verify(storage, never()).delete(any(), any());
    }

    @Test
    void deleteFileRejectsNonCompletedJob() {
        ExportJob job = new ExportJob();
        job.setId(9L);
        job.setSpaceId(1L);
        job.setStatus("FAILED");
        job.setFilePath("1/export.jsonl");

        assertThatThrownBy(() -> service.deleteFile(job))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态");
    }

    @Test
    void executeStoresPathFreeFailureReason() throws Exception {
        ExportJob job = new ExportJob();
        job.setId(5L);
        job.setSpaceId(1L);
        job.setExportType("CONVERSATIONS");
        job.setFormat("JSONL");
        job.setStatus("RUNNING");
        when(mapper.selectById(5L)).thenReturn(job);
        when(storage.store(any(), any(), any()))
                .thenThrow(new NoSuchFileException("data/exports/1/secret.jsonl"));
        when(mapper.updateById(any(ExportJob.class))).thenReturn(1);

        service.execute(5L);

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getErrorMessage()).isEqualTo("导出执行失败（NoSuchFileException）");
        assertThat(job.getErrorMessage()).doesNotContain("data/exports");
    }
}
