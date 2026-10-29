package com.king.ai.compliance.access;

import com.king.ai.common.JobLock;
import com.king.ai.system.SpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 季度访问复核任务。独立于 {@link AccessReviewService}，确保经由 Spring 代理调用
 * {@code @Transactional generate}，让“置旧复核为 SUPERSEDED + 插入新复核 + 审计”处于同一事务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessReviewScheduler {

    private static final String JOB_LOCK_NAME = "compliance-access-review";
    private static final Duration JOB_LOCK_TTL = Duration.ofHours(1);

    private final SpaceService spaceService;
    private final AccessReviewService accessReviewService;
    private final JobLock jobLock;

    @Scheduled(cron = "${king-ai.compliance.access-review.cron:0 0 4 1 1,4,7,10 *}")
    public void quarterly() {
        String owner = UUID.randomUUID().toString();
        if (!jobLock.tryAcquire(JOB_LOCK_NAME, owner, JOB_LOCK_TTL)) {
            log.info("Scheduled access review is running on another instance, skipping");
            return;
        }
        try {
            for (Long spaceId : spaceService.allSpaceIds()) {
                try {
                    accessReviewService.generate(spaceId, 0L);
                } catch (RuntimeException ex) {
                    // 单个空间失败不影响后续空间；只记录空间与异常类型，避免 SQL/PII 进入日志。
                    log.warn("Scheduled access review failed: spaceId={}, reason={}", spaceId,
                            ex.getClass().getSimpleName());
                }
            }
        } finally {
            jobLock.release(JOB_LOCK_NAME, owner);
        }
    }
}
