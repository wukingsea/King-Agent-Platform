package com.king.ai.compliance.access;

import com.king.ai.system.SpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 季度访问复核任务。独立于 {@link AccessReviewService}，确保经由 Spring 代理调用
 * {@code @Transactional generate}，让“置旧复核为 SUPERSEDED + 插入新复核 + 审计”处于同一事务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessReviewScheduler {

    private final SpaceService spaceService;
    private final AccessReviewService accessReviewService;

    @Scheduled(cron = "${king-ai.compliance.access-review.cron:0 0 4 1 1,4,7,10 *}")
    public void quarterly() {
        for (Long spaceId : spaceService.allSpaceIds()) {
            try {
                accessReviewService.generate(spaceId, 0L);
            } catch (RuntimeException ex) {
                // 单个空间失败不影响后续空间；只记录空间与异常类型，避免 SQL/PII 进入日志。
                log.warn("Scheduled access review failed: spaceId={}, reason={}", spaceId,
                        ex.getClass().getSimpleName());
            }
        }
    }
}
