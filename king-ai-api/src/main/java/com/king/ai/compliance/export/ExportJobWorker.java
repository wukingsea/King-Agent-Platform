package com.king.ai.compliance.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.compliance.retention.ExportJob;
import com.king.ai.compliance.retention.ExportJobMapper;
import com.king.ai.compliance.retention.LegalHoldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportJobWorker {

    private static final int MAX_REASON_CHARS = 900;

    private final ExportJobMapper mapper;
    private final ExportService exportService;
    private final LegalHoldService legalHoldService;

    @Scheduled(fixedDelayString = "${king-ai.compliance.export.poll-interval-ms:10000}")
    public void poll() {
        List<ExportJob> jobs = mapper.selectList(new LambdaQueryWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.PENDING.name())
                .orderByAsc(ExportJob::getId)
                .last("LIMIT 3"));
        for (ExportJob job : jobs) {
            int claimed = mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                    .eq(ExportJob::getId, job.getId())
                    .eq(ExportJob::getStatus, ExportStatus.PENDING.name())
                    .set(ExportJob::getStatus, ExportStatus.RUNNING.name())
                    .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
            if (claimed == 1) {
                exportService.execute(job.getId());
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
        List<ExportJob> expired = mapper.selectList(new LambdaQueryWrapper<ExportJob>()
                .eq(ExportJob::getStatus, ExportStatus.COMPLETED.name())
                .lt(ExportJob::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 100"));
        for (ExportJob job : expired) {
            if (legalHoldService.isHeld(job.getSpaceId(), "EXPORT", job.getId())) {
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
