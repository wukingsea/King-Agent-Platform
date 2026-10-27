package com.king.ai.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.entity.KnowledgeIndexJob;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.observability.KnowledgeMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@Profile("!migrate")
@RequiredArgsConstructor
public class KnowledgeIndexWorker {

    private final KnowledgeIndexQueue queue;
    private final KnowledgeService knowledgeService;
    private final KnowledgeDocMapper docMapper;
    private final KingAiProperties properties;
    private final KnowledgeMetrics metrics;

    @Qualifier("embeddingExecutor")
    private final ThreadPoolTaskExecutor embeddingExecutor;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIndexRequested(KnowledgeIndexRequestedEvent event) {
        submit(event.docId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.getIndex().isEnabled()) {
            return;
        }
        try {
            queue.recoverStale(staleBefore());
            reconcileDocs();
            submitDue();
            refreshQueueMetrics();
        } catch (RuntimeException ex) {
            log.error("Failed to recover knowledge index jobs", ex);
        }
    }

    @Scheduled(fixedDelayString = "${king-ai.index.poll-interval-ms:15000}")
    public void poll() {
        if (!properties.getIndex().isEnabled()) {
            return;
        }
        try {
            queue.recoverStale(staleBefore());
            submitDue();
            refreshQueueMetrics();
        } catch (RuntimeException ex) {
            log.error("Knowledge index poll failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${king-ai.index.reconcile-interval-ms:300000}")
    public void reconcile() {
        if (!properties.getIndex().isEnabled()) {
            return;
        }
        try {
            reconcileDocs();
            refreshQueueMetrics();
        } catch (RuntimeException ex) {
            log.error("Knowledge index reconcile failed", ex);
        }
    }

    private void submitDue() {
        int batch = Math.max(1, properties.getIndex().getBatchSize());
        for (KnowledgeIndexJob job : queue.due(batch)) {
            submit(job.getDocId());
        }
    }

    private void submit(Long docId) {
        if (docId == null || !inFlight.add(docId)) {
            return;
        }
        try {
            embeddingExecutor.execute(() -> {
                try {
                    process(docId);
                } catch (RuntimeException ex) {
                    log.error("Knowledge index worker failed: docId={}", docId, ex);
                } finally {
                    inFlight.remove(docId);
                }
            });
        } catch (RuntimeException ex) {
            inFlight.remove(docId);
            log.warn("Knowledge index task rejected: docId={}, {}", docId, ex.getMessage());
        }
    }

    private void process(Long docId) {
        long startedAt = System.nanoTime();
        String outcome = "skipped";
        try {
            KnowledgeIndexJob job = queue.find(docId);
            if (job == null || !KnowledgeIndexQueue.STATUS_PENDING.equals(job.getStatus())) {
                return;
            }
            int attempt = queue.claim(job);
            if (attempt == 0) {
                return;
            }
            try {
                KnowledgeDoc doc = docMapper.selectById(docId);
                if (doc == null) {
                    outcome = "orphaned";
                    queue.remove(docId);
                    return;
                }
                if (KnowledgeService.STATUS_READY.equals(doc.getStatus())) {
                    outcome = "already_ready";
                    queue.complete(docId);
                    return;
                }
                knowledgeService.indexDocument(docId);
                queue.complete(docId);
                outcome = "succeeded";
            } catch (PermanentIndexException ex) {
                outcome = "failed";
                String error = errorText(ex);
                knowledgeService.markDocFailed(docId, error);
                queue.failPermanently(docId, error);
                log.warn("Knowledge index permanently failed: docId={}, reason={}", docId, error);
            } catch (Exception ex) {
                String error = errorText(ex);
                int maxAttempts = Math.max(1, properties.getIndex().getMaxAttempts());
                if (attempt >= maxAttempts) {
                    outcome = "failed";
                    knowledgeService.markDocFailed(docId, error);
                    queue.failPermanently(docId, error);
                    log.error("Knowledge index failed after {} attempt(s): docId={}, reason={}",
                            attempt, docId, error);
                } else {
                    outcome = "retry";
                    knowledgeService.markDocPendingRetry(docId, "处理失败，将自动重试：" + error);
                    queue.retry(docId, nextRunAt(attempt), error);
                    log.warn("Knowledge index attempt {} failed, will retry: docId={}, reason={}",
                            attempt, docId, error);
                }
            }
        } finally {
            metrics.indexJob(outcome, System.nanoTime() - startedAt);
        }
    }

    private void refreshQueueMetrics() {
        try {
            QueueStats stats = queue.stats();
            metrics.updateQueue(stats.pending(), stats.failed(), stats.oldestPendingAt());
        } catch (RuntimeException ex) {
            log.warn("Failed to refresh knowledge queue metrics: {}", ex.getMessage());
        }
    }

    private void reconcileDocs() {
        LocalDateTime stale = staleBefore();
        int limit = Math.max(50, properties.getIndex().getBatchSize() * 20);
        List<KnowledgeDoc> docs = docMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .in(KnowledgeDoc::getStatus, KnowledgeService.STATUS_PENDING, KnowledgeService.STATUS_PROCESSING)
                .isNotNull(KnowledgeDoc::getContent)
                .orderByAsc(KnowledgeDoc::getId)
                .last("LIMIT " + limit));
        for (KnowledgeDoc snapshot : docs) {
            KnowledgeDoc doc = docMapper.selectById(snapshot.getId());
            if (doc == null || (!KnowledgeService.STATUS_PENDING.equals(doc.getStatus())
                    && !KnowledgeService.STATUS_PROCESSING.equals(doc.getStatus()))) {
                continue;
            }
            if (queue.existsActive(doc.getId())) {
                continue;
            }
            if (KnowledgeService.STATUS_PROCESSING.equals(doc.getStatus())
                    && doc.getUpdatedAt() != null && doc.getUpdatedAt().isAfter(stale)) {
                continue;
            }
            if (KnowledgeService.STATUS_PROCESSING.equals(doc.getStatus())) {
                knowledgeService.markDocPendingRetry(doc.getId(), "处理超时，已重新排队");
            }
            queue.enqueue(doc.getId());
        }
    }

    private LocalDateTime staleBefore() {
        return LocalDateTime.now().minusMinutes(Math.max(1, properties.getIndex().getStaleMinutes()));
    }

    private LocalDateTime nextRunAt(int attempt) {
        long baseSeconds = Math.max(1, properties.getIndex().getRetryDelaySeconds());
        long delaySeconds = baseSeconds * Math.min(Math.max(attempt, 1), 10);
        return LocalDateTime.now().plusSeconds(delaySeconds);
    }

    private String errorText(Exception ex) {
        return Texts.oneLine(Texts.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 900);
    }
}
