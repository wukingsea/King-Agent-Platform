package com.king.ai.compliance.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceSettingsService;
import com.king.ai.compliance.RedactionService;
import com.king.ai.compliance.dto.ExportCreateRequest;
import com.king.ai.compliance.dto.ExportJobView;
import com.king.ai.compliance.retention.ExportJob;
import com.king.ai.compliance.retention.ExportJobMapper;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final List<String> CONVERSATION_HEADERS = List.of(
            "session_id", "message_id", "role", "content", "status", "model", "created_at");
    private static final List<String> AUDIT_HEADERS = List.of(
            "id", "action", "actor_name", "resource_type", "resource_id", "status", "detail", "created_at");
    private static final List<String> MEMBER_HEADERS = List.of(
            "user_id", "username", "nickname", "role", "last_login_at");
    private static final int MAX_ERROR_MESSAGE_CHARS = 900;

    private final ExportJobMapper mapper;
    private final ExportStorage storage;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LegalHoldService legalHoldService;
    private final KingAiProperties properties;
    private final AuditLogService auditLogService;
    private final RedactionService redactionService;
    private final ComplianceSettingsService complianceSettingsService;

    public record Download(String fileName, long size, InputStream stream) {
    }

    @Transactional
    public ExportJobView create(Long spaceId, ExportCreateRequest request, Long operatorId) {
        ExportType type = parseType(request.type());
        ExportFormat format = parseFormat(request.format());
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw BizException.badRequest("导出时间范围不合法");
        }
        ExportJob job = new ExportJob();
        job.setSpaceId(spaceId);
        job.setExportType(type.name());
        job.setFormat(format.name());
        job.setStatus(ExportStatus.PENDING.name());
        job.setFromAt(request.from());
        job.setToAt(request.to());
        job.setRowCount(0L);
        job.setRequestedBy(operatorId);
        mapper.insert(job);
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.export_create",
                "export", String.valueOf(job.getId()), AuditLogService.STATUS_SUCCESS,
                "type=" + type.name() + ", format=" + format.name(), null, null, null);
        return view(job);
    }

    public PageResult<ExportJobView> page(Long spaceId, int page, int size) {
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        Page<ExportJob> result = mapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ExportJob>()
                        .eq(ExportJob::getSpaceId, spaceId)
                        .orderByDesc(ExportJob::getId));
        return PageResult.of(result.getRecords().stream().map(this::view).toList(),
                result.getTotal(), pageNo, pageSize);
    }

    public void execute(Long jobId) {
        ExportJob job = mapper.selectById(jobId);
        if (job == null || !ExportStatus.RUNNING.name().equals(job.getStatus())) {
            return;
        }
        job.setStartedAt(LocalDateTime.now());
        try {
            long[] written = new long[1];
            ExportStorage.StoredFile file = storage.store(job.getSpaceId(),
                    job.getFormat().toLowerCase(Locale.ROOT),
                    out -> written[0] = writeRows(job, out));
            job.setFileName("king-ai-" + job.getExportType().toLowerCase(Locale.ROOT)
                    + "-" + job.getId() + "." + job.getFormat().toLowerCase(Locale.ROOT));
            job.setFilePath(file.relativePath());
            job.setRowCount(written[0]);
            job.setStatus(ExportStatus.COMPLETED.name());
            job.setCompletedAt(LocalDateTime.now());
            job.setExpiresAt(LocalDateTime.now().plusHours(
                    Math.max(1, properties.getCompliance().getExport().getTtlHours())));
            job.setErrorMessage(null);
        } catch (Exception ex) {
            String reason = failureReason(ex);
            job.setStatus(ExportStatus.FAILED.name());
            job.setErrorMessage(reason);
            log.warn("Export job {} failed: {}", jobId, reason);
        }
        OptimisticLocks.requireUpdated(mapper.updateById(job), "导出任务");
        auditLogService.recordEvent(job.getSpaceId(), 0L, "SYSTEM", "compliance.export",
                "export", String.valueOf(jobId),
                ExportStatus.COMPLETED.name().equals(job.getStatus())
                        ? AuditLogService.STATUS_SUCCESS : AuditLogService.STATUS_FAILURE,
                "type=" + job.getExportType() + ", format=" + job.getFormat()
                        + ", rows=" + job.getRowCount(), null, null, null);
    }

    public Download openDownload(Long spaceId, Long jobId) {
        ExportJob job = mapper.selectById(jobId);
        if (job == null || !Objects.equals(job.getSpaceId(), spaceId)) {
            throw BizException.notFound("导出任务不存在");
        }
        if (!ExportStatus.COMPLETED.name().equals(job.getStatus())) {
            throw BizException.conflict("导出任务尚未完成");
        }
        if (job.getFilePath() == null) {
            throw BizException.notFound("导出文件不存在");
        }
        if (job.getExpiresAt() == null || !job.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw BizException.conflict("导出文件已过期，请重新生成");
        }
        try {
            return new Download(job.getFileName(), storage.size(spaceId, job.getFilePath()),
                    storage.open(spaceId, job.getFilePath()));
        } catch (IOException ex) {
            throw BizException.notFound("导出文件不存在");
        }
    }

    @Transactional
    public void deleteFile(ExportJob job) {
        try {
            if (job.getFilePath() != null) {
                storage.delete(job.getSpaceId(), job.getFilePath());
            }
        } catch (IOException ex) {
            throw BizException.conflict("导出文件删除失败");
        }
        mapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                .eq(ExportJob::getId, job.getId())
                .set(ExportJob::getFilePath, null)
                .set(ExportJob::getStatus, ExportStatus.EXPIRED.name())
                .set(ExportJob::getUpdatedAt, LocalDateTime.now()));
        auditLogService.recordEvent(job.getSpaceId(), 0L, "SYSTEM", "compliance.export.expire",
                "export", String.valueOf(job.getId()), AuditLogService.STATUS_SUCCESS,
                "type=" + job.getExportType(), null, null, null);
    }

    private long writeRows(ExportJob job, OutputStream out) throws IOException {
        ComplianceSettings settings = complianceSettingsService.effective(job.getSpaceId());
        long limit = Math.max(1, properties.getCompliance().getExport().getMaxRows());
        try (ExportSink sink = ExportSinkFactory.open(out,
                ExportFormat.valueOf(job.getFormat()), headers(job.getExportType()), objectMapper)) {
            switch (ExportType.valueOf(job.getExportType())) {
                case CONVERSATIONS -> writeConversations(job, sink, redactionService, settings, limit);
                case AUDIT -> writeAudit(job, sink, limit);
                case MEMBERS -> writeMembers(job, sink, limit);
            }
            return sink.count();
        }
    }

    private void writeConversations(ExportJob job, ExportSink sink, RedactionService redaction,
                                    ComplianceSettings settings, long limit) throws IOException {
        List<Long> sessionIds = jdbcTemplate.queryForList(
                "SELECT id FROM king_ai_chat_session WHERE space_id = ? "
                        + "AND COALESCE(last_message_at, created_at) >= ? "
                        + "AND COALESCE(last_message_at, created_at) <= ? "
                        + "ORDER BY id LIMIT ?",
                Long.class, job.getSpaceId(), fromOrEpoch(job.getFromAt()), toOrMax(job.getToAt()),
                limit);
        for (Long sessionId : sessionIds) {
            if (sink.count() >= limit) {
                break;
            }
            jdbcTemplate.query("SELECT id, session_id, role, content, status, model, created_at "
                            + "FROM king_ai_chat_message WHERE session_id = ? ORDER BY id",
                    rs -> {
                        if (sink.count() >= limit) {
                            return;
                        }
                        try {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("session_id", sessionId);
                            row.put("message_id", rs.getLong("id"));
                            row.put("role", rs.getString("role"));
                            row.put("content", redaction.redact(rs.getString("content"), settings).text());
                            row.put("status", rs.getString("status"));
                            row.put("model", rs.getString("model"));
                            row.put("created_at", String.valueOf(rs.getTimestamp("created_at")));
                            sink.row(row);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }, sessionId);
        }
    }

    private void writeAudit(ExportJob job, ExportSink sink, long limit) throws IOException {
        jdbcTemplate.query(
                "SELECT id, action, actor_name, resource_type, resource_id, status, detail, created_at "
                        + "FROM king_ai_audit_log WHERE space_id = ? "
                        + "AND created_at >= ? AND created_at <= ? "
                        + "ORDER BY id LIMIT ?",
                rs -> {
                    if (sink.count() >= limit) {
                        return;
                    }
                    try {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("action", rs.getString("action"));
                        row.put("actor_name", rs.getString("actor_name"));
                        row.put("resource_type", rs.getString("resource_type"));
                        row.put("resource_id", rs.getString("resource_id"));
                        row.put("status", rs.getString("status"));
                        row.put("detail", rs.getString("detail"));
                        row.put("created_at", String.valueOf(rs.getTimestamp("created_at")));
                        sink.row(row);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                },
                job.getSpaceId(), fromOrEpoch(job.getFromAt()), toOrMax(job.getToAt()), limit);
    }

    private void writeMembers(ExportJob job, ExportSink sink, long limit) throws IOException {
        jdbcTemplate.query(
                "SELECT us.user_id, u.username, u.nickname, us.role, u.last_login_at "
                        + "FROM king_ai_user_space us JOIN king_ai_user u ON u.id = us.user_id "
                        + "WHERE us.space_id = ? ORDER BY us.user_id LIMIT ?",
                rs -> {
                    if (sink.count() >= limit) {
                        return;
                    }
                    try {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("user_id", rs.getLong("user_id"));
                        row.put("username", rs.getString("username"));
                        row.put("nickname", rs.getString("nickname"));
                        row.put("role", rs.getString("role"));
                        row.put("last_login_at", String.valueOf(rs.getTimestamp("last_login_at")));
                        sink.row(row);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }, job.getSpaceId(), limit);
    }

    private static List<String> headers(String exportType) {
        return switch (ExportType.valueOf(exportType)) {
            case CONVERSATIONS -> CONVERSATION_HEADERS;
            case AUDIT -> AUDIT_HEADERS;
            case MEMBERS -> MEMBER_HEADERS;
        };
    }

    private static ExportType parseType(String value) {
        try {
            return ExportType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("导出类型仅支持 CONVERSATIONS/AUDIT/MEMBERS");
        }
    }

    private static ExportFormat parseFormat(String value) {
        try {
            return ExportFormat.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("导出格式仅支持 JSONL/CSV");
        }
    }

    private static LocalDateTime fromOrEpoch(LocalDateTime value) {
        return value == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : value;
    }

    private static LocalDateTime toOrMax(LocalDateTime value) {
        return value == null ? LocalDateTime.of(9999, 12, 31, 23, 59, 59) : value;
    }

    /**
     * Builds a bounded, path-free failure reason. Only our own {@link BizException} messages are
     * surfaced verbatim; every other exception is reduced to its class name so filesystem paths
     * from {@code NoSuchFileException}/{@code FileSystemException} never reach the database or logs.
     */
    private static String failureReason(Exception ex) {
        String reason = ex instanceof BizException biz
                ? biz.getMessage()
                : "导出执行失败（" + ex.getClass().getSimpleName() + "）";
        return Texts.oneLine(Texts.blankToDefault(reason, "导出执行失败"), MAX_ERROR_MESSAGE_CHARS);
    }

    private ExportJobView view(ExportJob job) {
        return new ExportJobView(job.getId(), job.getExportType(), job.getFormat(), job.getStatus(),
                job.getFileName(), job.getRowCount(), job.getErrorMessage(), job.getRequestedBy(),
                job.getFromAt(), job.getToAt(), job.getExpiresAt(), job.getCreatedAt(),
                job.getCompletedAt());
    }
}
