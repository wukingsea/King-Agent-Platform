package com.king.ai.audit;

import com.king.ai.common.JobLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Schedules the legacy global audit purge under a distributed lock so multiple replicas cannot
 * delete the same range concurrently or race on retention bookkeeping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPurgeJob {

    private static final String JOB_LOCK_NAME = "audit-retention";
    private static final Duration JOB_LOCK_TTL = Duration.ofHours(1);

    private final AuditLogService auditLogService;
    private final JobLock jobLock;

    @Scheduled(cron = "${king-ai.audit.retention-cron:0 30 3 * * *}")
    public void run() {
        String owner = UUID.randomUUID().toString();
        if (!jobLock.tryAcquire(JOB_LOCK_NAME, owner, JOB_LOCK_TTL)) {
            log.info("审计清理任务已由其他实例执行，跳过本轮");
            return;
        }
        try {
            auditLogService.purgeExpired();
        } finally {
            jobLock.release(JOB_LOCK_NAME, owner);
        }
    }
}
