package com.king.ai.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.king.ai.common.Texts;
import com.king.ai.knowledge.entity.KnowledgeIndexJob;
import com.king.ai.knowledge.mapper.KnowledgeIndexJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexQueue {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FAILED = "FAILED";

    private final KnowledgeIndexJobMapper mapper;
    private final ApplicationEventPublisher publisher;

    public KnowledgeIndexJob find(Long docId) {
        return mapper.selectOne(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getDocId, docId));
    }

    public void enqueue(Long docId) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeIndexJob existing = find(docId);
        if (existing == null) {
            KnowledgeIndexJob job = new KnowledgeIndexJob();
            job.setDocId(docId);
            job.setStatus(STATUS_PENDING);
            job.setAttempts(0);
            job.setNextRunAt(now);
            job.setLastError("");
            try {
                mapper.insert(job);
            } catch (DuplicateKeyException ex) {
                existing = find(docId);
                if (existing == null) {
                    throw ex;
                }
                reset(existing, now);
            }
        } else if (!STATUS_RUNNING.equals(existing.getStatus())) {
            reset(existing, now);
        } else {
            return;
        }
        publisher.publishEvent(new KnowledgeIndexRequestedEvent(docId));
    }

    private void reset(KnowledgeIndexJob job, LocalDateTime now) {
        KnowledgeIndexJob patch = new KnowledgeIndexJob();
        patch.setId(job.getId());
        patch.setStatus(STATUS_PENDING);
        patch.setAttempts(0);
        patch.setNextRunAt(now);
        patch.setLastError("");
        mapper.updateById(patch);
    }

    public int claim(KnowledgeIndexJob job) {
        int nextAttempt = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;
        KnowledgeIndexJob patch = new KnowledgeIndexJob();
        patch.setStatus(STATUS_RUNNING);
        patch.setAttempts(nextAttempt);
        patch.setLastError("");
        int updated = mapper.update(patch, new LambdaUpdateWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getId, job.getId())
                .eq(KnowledgeIndexJob::getStatus, STATUS_PENDING)
                .le(KnowledgeIndexJob::getNextRunAt, LocalDateTime.now()));
        return updated > 0 ? nextAttempt : 0;
    }

    public void complete(Long docId) {
        remove(docId);
    }

    public void retry(Long docId, LocalDateTime nextRunAt, String error) {
        KnowledgeIndexJob patch = new KnowledgeIndexJob();
        patch.setStatus(STATUS_PENDING);
        patch.setNextRunAt(nextRunAt);
        patch.setLastError(Texts.oneLine(error, 900));
        mapper.update(patch, new LambdaUpdateWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getDocId, docId));
    }

    public void failPermanently(Long docId, String error) {
        KnowledgeIndexJob patch = new KnowledgeIndexJob();
        patch.setStatus(STATUS_FAILED);
        patch.setLastError(Texts.oneLine(error, 900));
        mapper.update(patch, new LambdaUpdateWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getDocId, docId));
    }

    public void recoverStale(LocalDateTime before) {
        KnowledgeIndexJob patch = new KnowledgeIndexJob();
        patch.setStatus(STATUS_PENDING);
        patch.setNextRunAt(LocalDateTime.now());
        patch.setLastError("处理超时，已重新排队");
        int recovered = mapper.update(patch, new LambdaUpdateWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getStatus, STATUS_RUNNING)
                .lt(KnowledgeIndexJob::getUpdatedAt, before));
        if (recovered > 0) {
            log.warn("Recovered {} stale knowledge index job(s)", recovered);
        }
    }

    public List<KnowledgeIndexJob> due(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getStatus, STATUS_PENDING)
                .le(KnowledgeIndexJob::getNextRunAt, LocalDateTime.now())
                .orderByAsc(KnowledgeIndexJob::getNextRunAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public boolean existsActive(Long docId) {
        return mapper.selectCount(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getDocId, docId)
                .in(KnowledgeIndexJob::getStatus, STATUS_PENDING, STATUS_RUNNING)) > 0;
    }

    public void remove(Long docId) {
        mapper.delete(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getDocId, docId));
    }
}
