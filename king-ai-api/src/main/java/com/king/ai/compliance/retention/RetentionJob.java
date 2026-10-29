package com.king.ai.compliance.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.JobLock;
import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceSettingsService;
import com.king.ai.compliance.RedactionService;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * 按空间与资源类型执行保留策略清理。法律保留优先于一切删除：每次删除前都逐条检查，
 * 且不依赖批量快照，以便观察到长时间清理过程中新建的保留。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionJob {

    private static final String JOB_LOCK_NAME = "compliance-retention";
    private static final Duration JOB_LOCK_TTL = Duration.ofHours(2);

    private final RetentionPolicyService policyService;
    private final JdbcTemplate jdbcTemplate;
    private final LegalHoldService legalHoldService;
    private final AuditLogService auditLogService;
    private final JobLock jobLock;
    private final KingAiProperties properties;
    private final ExportStorage storage;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RedactionService redactionService;
    private final ComplianceSettingsService complianceSettingsService;

    @Scheduled(cron = "${king-ai.compliance.retention.cron:0 0 3 * * *}")
    public void run() {
        if (!properties.getCompliance().getRetention().isEnabled()) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        if (!jobLock.tryAcquire(JOB_LOCK_NAME, owner, JOB_LOCK_TTL)) {
            log.info("保留清理任务已由其他实例执行，跳过本轮");
            return;
        }
        try {
            boolean dryRun = properties.getCompliance().getRetention().isDryRun();
            for (Long spaceId : policyService.spacesWithPolicies()) {
                for (RetentionPolicy policy : policyService.enabledPolicies(spaceId)) {
                    try {
                        purge(spaceId, policy, dryRun, 0L);
                    } catch (RuntimeException ex) {
                        log.warn("保留清理任务执行失败：spaceId={}, type={}, reason={}",
                                spaceId, policy.getResourceType(), ex.getClass().getSimpleName());
                    }
                }
            }
        } finally {
            jobLock.release(JOB_LOCK_NAME, owner);
        }
    }

    public void purge(Long spaceId, RetentionPolicy policy, boolean dryRun, Long operatorId) {
        int days = policy.getRetentionDays() == null ? 1 : policy.getRetentionDays();
        LocalDateTime cutoff = RetentionPolicyEvaluator.cutoff(LocalDateTime.now(), days);
        try {
            RetentionType type = RetentionType.valueOf(policy.getResourceType());
            int deleted = switch (type) {
                case SESSION -> purgeSessions(spaceId, cutoff, policy, dryRun);
                case MESSAGE -> purgeMessages(spaceId, cutoff, dryRun);
                case AUDIT -> purgeAudit(spaceId, cutoff, dryRun);
                case EXPORT -> purgeExports(spaceId, dryRun);
            };
            auditLogService.recordEvent(spaceId, operatorId == null ? 0L : operatorId, "SYSTEM",
                    "compliance.retention", "retention", type.name(),
                    AuditLogService.STATUS_SUCCESS,
                    "deleted=" + deleted + ", dryRun=" + dryRun + ", cutoff=" + cutoff,
                    null, null, null);
            // 指标是尽力而为的观测信号，不能因其失败把已成功的清理记成失败审计。
            try {
                meterRegistry.counter("king.ai.compliance.retention.deleted",
                        "type", type.name(), "dryRun", Boolean.toString(dryRun)).increment(deleted);
            } catch (RuntimeException ex) {
                log.warn("保留清理指标记录失败：type={}, reason={}",
                        type.name(), ex.getClass().getSimpleName());
            }
        } catch (RuntimeException ex) {
            // 清理中途失败时必须留下失败证据，否则审计会误报为成功；异常继续上抛由调度器记录。
            auditLogService.recordEvent(spaceId, operatorId == null ? 0L : operatorId, "SYSTEM",
                    "compliance.retention", "retention", policy.getResourceType(),
                    AuditLogService.STATUS_FAILURE,
                    "dryRun=" + dryRun + ", cutoff=" + cutoff
                            + ", error=" + ex.getClass().getSimpleName(),
                    null, null, null);
            throw ex;
        }
    }

    /**
     * 以 id 游标分批清理会话，避免队首大量被保留的会话把后续可删会话永久堵住。
     * 归档成功后、删除前再次逐条检查法律保留，缩小“归档→删除”窗口内新增保留被误删的风险。
     */
    private int purgeSessions(Long spaceId, LocalDateTime cutoff, RetentionPolicy policy,
                              boolean dryRun) {
        boolean archive = policy.getArchiveBeforeDelete() != null
                && policy.getArchiveBeforeDelete() == 1;
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        boolean truncated = true;
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM king_ai_chat_session WHERE space_id = ? "
                            + "AND COALESCE(last_message_at, created_at) < ? AND id > ? "
                            + "ORDER BY id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
                truncated = false;
                break;
            }
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (!legalHoldService.isSessionHeld(spaceId, id)) {
                    ids.add(id);
                }
            }
            boolean shortBatch = rows.size() < batch;
            boolean archived = ids.isEmpty() || dryRun || !archive
                    || archiveSessions(spaceId, ids);
            if (!ids.isEmpty() && archived) {
                if (dryRun) {
                    total += ids.size();
                } else {
                    List<Long> deletable = new ArrayList<>(ids.size());
                    for (Long id : ids) {
                        if (!legalHoldService.isSessionHeld(spaceId, id)) {
                            deletable.add(id);
                        }
                    }
                    deleteSessions(deletable);
                    total += deletable.size();
                }
            }
            if (shortBatch) {
                truncated = false;
                break;
            }
        }
        if (truncated) {
            warnTruncated(spaceId, "SESSION", lastId);
        }
        return total;
    }

    private boolean archiveSessions(Long spaceId, List<Long> sessionIds) {
        ComplianceSettings settings = complianceSettingsService.effective(spaceId);
        try {
            storage.store(spaceId, "gz", out -> writeArchive(out, sessionIds, settings));
            return true;
        } catch (IOException | RuntimeException ex) {
            count("archive_failed", "SESSION");
            log.warn("保留归档失败：spaceId={}, sessions={}, reason={}",
                    spaceId, sessionIds.size(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private void writeArchive(OutputStream out, List<Long> sessionIds,
                              ComplianceSettings settings) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            for (Long sessionId : sessionIds) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sessionId", sessionId);
                payload.put("messages", archiveMessages(sessionId, settings));
                gzip.write(objectMapper.writeValueAsBytes(payload));
                gzip.write('\n');
            }
        }
    }

    private List<Map<String, Object>> archiveMessages(Long sessionId, ComplianceSettings settings) {
        return jdbcTemplate.query(
                "SELECT id, role, content, status, model, created_at FROM king_ai_chat_message "
                        + "WHERE session_id = ? ORDER BY id",
                (rs, rowNum) -> archiveMessage(rs, settings), sessionId);
    }

    private Map<String, Object> archiveMessage(ResultSet rs, ComplianceSettings settings)
            throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("role", rs.getString("role"));
        row.put("content", redactionService.redact(rs.getString("content"), settings).text());
        row.put("status", rs.getString("status"));
        row.put("model", rs.getString("model"));
        row.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
        return row;
    }

    /**
     * 消息表通过 V6 的 fk_message_session ... ON DELETE CASCADE 随会话级联删除，
     * 因此用单条语句删除会话即可避免两条自动提交语句之间留下空会话的中间态。
     */
    private void deleteSessions(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM king_ai_chat_session WHERE id IN ("
                + placeholders(sessionIds.size()) + ")", sessionIds.toArray());
    }

    private int purgeMessages(Long spaceId, LocalDateTime cutoff, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        boolean truncated = true;
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT m.id, m.session_id FROM king_ai_chat_message m "
                            + "JOIN king_ai_chat_session s ON s.id = m.session_id "
                            + "WHERE s.space_id = ? AND m.created_at < ? AND m.id > ? "
                            + "ORDER BY m.id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
                truncated = false;
                break;
            }
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                Long sessionId = toLong(row.get("session_id"));
                if (id == null || sessionId == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (!legalHoldService.isMessageHeld(spaceId, id, sessionId)) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                if (!dryRun) {
                    jdbcTemplate.update("DELETE FROM king_ai_chat_message WHERE id IN ("
                            + placeholders(ids.size()) + ")", ids.toArray());
                }
                total += ids.size();
            }
            if (rows.size() < batch) {
                truncated = false;
                break;
            }
        }
        if (truncated) {
            warnTruncated(spaceId, "MESSAGE", lastId);
        }
        return total;
    }

    /**
     * 审计日志同样按 id 游标分批，避免一次 DELETE 锁住整表；法律保留逐条复查，
     * 不使用批量快照，以观察到清理过程中新建的保留。
     */
    private int purgeAudit(Long spaceId, LocalDateTime cutoff, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        boolean truncated = true;
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM king_ai_audit_log WHERE space_id = ? AND created_at < ? "
                            + "AND id > ? ORDER BY id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
                truncated = false;
                break;
            }
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (!legalHoldService.isHeld(spaceId, "AUDIT", id)) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                total += dryRun ? ids.size() : auditLogService.purgeByIds(spaceId, ids);
            }
            if (rows.size() < batch) {
                truncated = false;
                break;
            }
        }
        if (truncated) {
            warnTruncated(spaceId, "AUDIT", lastId);
        }
        return total;
    }

    /**
     * 过期导出文件的安全网：文件 TTL 由 {@code ExportJobWorker#expire()} 负责，
     * 此处只清理仍停留在 COMPLETED 的过期任务（例如工作线程删除文件失败或实例重启后遗留）。
     * EXPORT 策略的 retention_days 不参与文件过期判定，文件生命周期始终由 TTL 控制。
     */
    private int purgeExports(Long spaceId, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        LocalDateTime now = LocalDateTime.now();
        boolean truncated = true;
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, file_path FROM king_ai_export_job WHERE space_id = ? "
                            + "AND status = 'COMPLETED' AND expires_at IS NOT NULL "
                            + "AND expires_at < ? AND id > ? ORDER BY id LIMIT ?",
                    spaceId, now, lastId, batch);
            if (rows.isEmpty()) {
                truncated = false;
                break;
            }
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id == null) {
                    continue;
                }
                lastId = Math.max(lastId, id);
                if (legalHoldService.isHeld(spaceId, "EXPORT", id)) {
                    continue;
                }
                if (dryRun) {
                    total++;
                    continue;
                }
                String path = row.get("file_path") == null ? null
                        : String.valueOf(row.get("file_path"));
                try {
                    if (path != null && !path.isBlank()) {
                        storage.delete(spaceId, path);
                    }
                } catch (IOException | IllegalArgumentException ex) {
                    // 先删文件再更新状态：文件删除失败时保留 COMPLETED，下一轮重试。
                    log.warn("过期导出文件删除失败：spaceId={}, jobId={}, reason={}",
                            spaceId, id, ex.getClass().getSimpleName());
                    continue;
                }
                jdbcTemplate.update("UPDATE king_ai_export_job SET status = 'EXPIRED', "
                                + "file_path = NULL, updated_at = ? "
                                + "WHERE id = ? AND status = 'COMPLETED'",
                        LocalDateTime.now(), id);
                total++;
            }
            if (rows.size() < batch) {
                truncated = false;
                break;
            }
        }
        if (truncated) {
            warnTruncated(spaceId, "EXPORT", lastId);
        }
        return total;
    }

    private int batchSize() {
        return Math.max(1, properties.getCompliance().getRetention().getBatchSize());
    }

    private int maxBatches() {
        return Math.max(1, properties.getCompliance().getRetention().getMaxBatches());
    }

    private void warnTruncated(Long spaceId, String type, long lastId) {
        count("truncated", type);
        log.warn("保留清理达到单轮批次上限，剩余数据将在下次运行继续：spaceId={}, type={}, lastId={}",
                spaceId, type, lastId);
    }

    /** 指标是尽力而为的观测信号，任何注册表异常都不能影响清理结果与审计。 */
    private void count(String metric, String type) {
        try {
            meterRegistry.counter("king.ai.compliance.retention." + metric, "type", type)
                    .increment();
        } catch (RuntimeException ex) {
            log.warn("保留清理指标记录失败：metric={}, type={}, reason={}",
                    metric, type, ex.getClass().getSimpleName());
        }
    }

    private static String placeholders(int count) {
        return count <= 0 ? "" : "?" + ",?".repeat(count - 1);
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
