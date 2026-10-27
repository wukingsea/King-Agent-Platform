package com.king.ai.compliance.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.audit.AuditLogService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * 按空间与资源类型执行保留策略清理。法律保留优先于一切删除：每次删除前都逐条检查，
 * 且不依赖批量快照，以便观察到长时间清理过程中新建的保留。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionJob {

    private final RetentionPolicyService policyService;
    private final JdbcTemplate jdbcTemplate;
    private final LegalHoldService legalHoldService;
    private final AuditLogService auditLogService;
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
    }

    public void purge(Long spaceId, RetentionPolicy policy, boolean dryRun, Long operatorId) {
        RetentionType type = RetentionType.valueOf(policy.getResourceType());
        int days = policy.getRetentionDays() == null ? 1 : policy.getRetentionDays();
        LocalDateTime cutoff = RetentionPolicyEvaluator.cutoff(LocalDateTime.now(), days);
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
        meterRegistry.counter("king.ai.compliance.retention.deleted",
                "type", type.name()).increment(deleted);
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
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM king_ai_chat_session WHERE space_id = ? "
                            + "AND COALESCE(last_message_at, created_at) < ? AND id > ? "
                            + "ORDER BY id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
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
            if (!ids.isEmpty()) {
                if (!dryRun && archive && !archiveSessions(spaceId, ids)) {
                    continue;
                }
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
            if (rows.size() < batch) {
                break;
            }
        }
        return total;
    }

    private boolean archiveSessions(Long spaceId, List<Long> sessionIds) {
        ComplianceSettings settings = complianceSettingsService.effective(spaceId);
        try {
            storage.store(spaceId, "jsonl.gz", out -> writeArchive(out, sessionIds, settings));
            return true;
        } catch (IOException | RuntimeException ex) {
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
     * 消息表没有 space_id 也没有级联外键，删除会话前必须显式删除其消息。
     */
    private void deleteSessions(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        String in = placeholders(sessionIds.size());
        Object[] args = sessionIds.toArray();
        jdbcTemplate.update("DELETE FROM king_ai_chat_message WHERE session_id IN (" + in + ")", args);
        jdbcTemplate.update("DELETE FROM king_ai_chat_session WHERE id IN (" + in + ")", args);
    }

    private int purgeMessages(Long spaceId, LocalDateTime cutoff, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT m.id, m.session_id FROM king_ai_chat_message m "
                            + "JOIN king_ai_chat_session s ON s.id = m.session_id "
                            + "WHERE s.space_id = ? AND m.created_at < ? AND m.id > ? "
                            + "ORDER BY m.id LIMIT ?",
                    spaceId, cutoff, lastId, batch);
            if (rows.isEmpty()) {
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
                break;
            }
        }
        return total;
    }

    private int purgeAudit(Long spaceId, LocalDateTime cutoff, boolean dryRun) {
        Set<Long> held = legalHoldService.activeResourceIds(spaceId, "AUDIT");
        if (!dryRun) {
            return auditLogService.purgeBefore(spaceId, cutoff, held);
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM king_ai_audit_log WHERE space_id = ? AND created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(spaceId);
        args.add(cutoff);
        if (!held.isEmpty()) {
            sql.append(" AND id NOT IN (").append(placeholders(held.size())).append(')');
            args.addAll(held);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count.intValue();
    }

    private int purgeExports(Long spaceId, boolean dryRun) {
        int total = 0;
        long lastId = 0L;
        int batch = batchSize();
        LocalDateTime now = LocalDateTime.now();
        for (int round = 0; round < maxBatches(); round++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, file_path FROM king_ai_export_job WHERE space_id = ? "
                            + "AND status = 'COMPLETED' AND expires_at IS NOT NULL "
                            + "AND expires_at < ? AND id > ? ORDER BY id LIMIT ?",
                    spaceId, now, lastId, batch);
            if (rows.isEmpty()) {
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
                break;
            }
        }
        return total;
    }

    private int batchSize() {
        return Math.max(1, properties.getCompliance().getRetention().getBatchSize());
    }

    private int maxBatches() {
        return Math.max(1, properties.getCompliance().getRetention().getMaxBatches());
    }

    private static String placeholders(int count) {
        return count <= 0 ? "" : "?" + ",?".repeat(count - 1);
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
