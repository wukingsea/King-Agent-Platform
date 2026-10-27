package com.king.ai.compliance.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.compliance.retention.ExportJob;
import com.king.ai.compliance.retention.ExportJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@Profile("!migrate")
@RequiredArgsConstructor
public class ExportJobWorker {

    private static final int MAX_REASON_CHARS = 900;

    private final ExportJobMapper mapper;
    private final ExportService exportService;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString = "${king-ai.compliance.export.poll-interval-ms:10000}")
    public void poll() {
        List<ExportJob> jobs = mapper.selectList(new LambdaQueryWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.PENDING.name())
                .orderByAsc(ExportJob::getId)
                .last("LIMIT 3"));
        for (ExportJob job : jobs) {
            LocalDateTime now = LocalDateTime.now();
            int claimed = mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                    .eq(ExportJob::getId, job.getId())
                    .eq(ExportJob::getStatus, ExportStatus.PENDING.name())
                    .set(ExportJob::getStatus, ExportStatus.RUNNING.name())
                    .set(ExportJob::getStartedAt, now)
                    .set(ExportJob::getUpdatedAt, now));
            if (claimed == 1) {
                try {
                    exportService.execute(job.getId());
                } catch (RuntimeException ex) {
                    // One broken job must not abort the batch. Never surface the message: it may
                    // carry filesystem paths from the storage layer.
                    log.warn("Export job {} aborted with {}", job.getId(), ex.getClass().getSimpleName());
                    mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                            .eq(ExportJob::getId, job.getId())
                            .eq(ExportJob::getStatus, ExportStatus.RUNNING.name())
                            .set(ExportJob::getStatus, ExportStatus.FAILED.name())
                            .set(ExportJob::getErrorMessage, Texts.oneLine(
                                    "导出执行失败（" + ex.getClass().getSimpleName() + "）", MAX_REASON_CHARS))
                            .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
                }
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStale() {
        mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.RUNNING.name())
                .lt(ExportJob::getUpdatedAt, LocalDateTime.now().minusMinutes(30))
                .set(ExportJob::getStatus, ExportStatus.PENDING.name())
                .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void expire() {
        LocalDateTime now = LocalDateTime.now();
        // Legal-hold filtering happens in SQL: a held job must not consume the LIMIT 100 window
        // and starve the jobs behind it.
        List<Long> candidateIds = jdbcTemplate.queryForList(
                "SELECT e.id FROM king_ai_export_job e WHERE e.status = ? AND e.expires_at IS NOT NULL "
                        + "AND e.expires_at < ? AND NOT EXISTS (SELECT 1 FROM king_ai_legal_hold h "
                        + "WHERE h.space_id = e.space_id AND h.resource_type = 'EXPORT' "
                        + "AND h.resource_id = e.id AND h.released_at IS NULL "
                        + "AND (h.until IS NULL OR h.until > ?)) ORDER BY e.id LIMIT 100",
                Long.class, ExportStatus.COMPLETED.name(), now, now);
        for (Long id : candidateIds) {
            ExportJob job = mapper.selectById(id);
            if (job == null) {
                continue;
            }
            try {
                exportService.deleteFile(job);
            } catch (RuntimeException ex) {
                log.warn("Failed to delete expired export {}: {}", job.getId(), failureReason(ex));
            }
        }
    }

    /**
     * Bounded, path-free reason: only our own {@link BizException} messages are surfaced verbatim.
     */
    private static String failureReason(RuntimeException ex) {
        String reason = ex instanceof BizException biz
                ? biz.getMessage()
                : ex.getClass().getSimpleName();
        return Texts.oneLine(Texts.blankToDefault(reason, "导出文件删除失败"), MAX_REASON_CHARS);
    }
}
