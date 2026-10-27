package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.compliance.dto.LegalHoldCreateRequest;
import com.king.ai.compliance.dto.LegalHoldView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LegalHoldService {

    private static final Set<String> TYPES = Set.of("SESSION", "MESSAGE", "AUDIT", "EXPORT");
    private static final int MAX_REASON_CHARS = 500;

    private final LegalHoldMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public List<LegalHoldView> list(Long spaceId) {
        return mapper.selectList(new LambdaQueryWrapper<LegalHold>()
                        .eq(LegalHold::getSpaceId, spaceId)
                        .orderByDesc(LegalHold::getId))
                .stream().map(this::view).toList();
    }

    @Transactional
    public LegalHoldView create(Long spaceId, LegalHoldCreateRequest request, Long operatorId) {
        String type = request.resourceType() == null
                ? "" : request.resourceType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw BizException.badRequest("保留类型仅支持 SESSION/MESSAGE/AUDIT/EXPORT");
        }
        if (request.resourceId() == null) {
            throw BizException.badRequest("缺少资源 ID");
        }
        if (Texts.isBlank(request.reason())) {
            throw BizException.badRequest("请填写法律保留原因");
        }
        if (request.until() != null && !request.until().isAfter(LocalDateTime.now())) {
            throw BizException.badRequest("法律保留截止时间必须晚于当前时间");
        }
        LegalHold hold = new LegalHold();
        hold.setSpaceId(spaceId);
        hold.setResourceType(type);
        hold.setResourceId(request.resourceId());
        hold.setReason(Texts.trimTo(request.reason(), MAX_REASON_CHARS));
        hold.setUntil(request.until());
        hold.setReleasedAt(null);
        hold.setReleasedBy(null);
        mapper.insert(hold);
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.legal_hold_create",
                "legal_hold", String.valueOf(hold.getId()), AuditLogService.STATUS_SUCCESS,
                "resourceType=" + type + ", resourceId=" + request.resourceId()
                        + ", until=" + request.until(), null, null, null);
        return view(hold);
    }

    @Transactional
    public void release(Long spaceId, Long holdId, Long operatorId) {
        LegalHold hold = mapper.selectById(holdId);
        if (hold == null || !Objects.equals(hold.getSpaceId(), spaceId)) {
            throw BizException.notFound("法律保留不存在");
        }
        if (hold.getReleasedAt() != null) {
            throw BizException.conflict("法律保留已解除");
        }
        hold.setReleasedAt(LocalDateTime.now());
        hold.setReleasedBy(operatorId);
        OptimisticLocks.requireUpdated(mapper.updateById(hold), "法律保留");
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.legal_hold_release",
                "legal_hold", String.valueOf(holdId), AuditLogService.STATUS_SUCCESS,
                "resourceType=" + hold.getResourceType() + ", resourceId=" + hold.getResourceId(),
                null, null, null);
    }

    private LegalHoldView view(LegalHold hold) {
        return new LegalHoldView(hold.getId(), hold.getResourceType(), hold.getResourceId(),
                hold.getReason(), hold.getUntil(), hold.getReleasedAt(), hold.getCreatedAt());
    }

    /**
     * 判断某个资源是否存在生效中的法律保留；类型会先做 trim + 大写规范化后再精确匹配。
     * <p>只匹配完全一致的资源类型，{@code SESSION} 保留不会自动扩展到该会话下的消息：
     * 删除会话请用 {@link #isSessionHeld(Long, Long)}（同时检查属于该会话的 {@code MESSAGE} 保留），
     * 删除单条消息请用 {@link #isMessageHeld(Long, Long, Long)}（消息保留或其所属会话保留）。
     */
    public boolean isHeld(Long spaceId, String resourceType, Long resourceId) {
        String type = normalizeType(resourceType);
        Long count = mapper.selectCount(new LambdaQueryWrapper<LegalHold>()
                .eq(LegalHold::getSpaceId, spaceId)
                .eq(LegalHold::getResourceType, type)
                .eq(LegalHold::getResourceId, resourceId)
                .isNull(LegalHold::getReleasedAt)
                .and(w -> w.isNull(LegalHold::getUntil)
                        .or().gt(LegalHold::getUntil, LocalDateTime.now())));
        return count != null && count > 0;
    }

    /**
     * 判断会话是否被保留：命中该会话的 {@code SESSION} 保留，或任一属于该会话的消息存在 {@code MESSAGE} 保留。
     */
    public boolean isSessionHeld(Long spaceId, Long sessionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM king_ai_legal_hold h WHERE h.space_id = ? "
                        + "AND h.released_at IS NULL AND (h.until IS NULL OR h.until > ?) "
                        + "AND ((h.resource_type = 'SESSION' AND h.resource_id = ?) "
                        + "OR (h.resource_type = 'MESSAGE' AND h.resource_id IN "
                        + "(SELECT id FROM king_ai_chat_message WHERE session_id = ?)))",
                Long.class, spaceId, LocalDateTime.now(), sessionId, sessionId);
        return count != null && count > 0;
    }

    /**
     * 判断单条消息是否被保留：消息自身存在 {@code MESSAGE} 保留，或其所属会话存在 {@code SESSION} 保留。
     */
    public boolean isMessageHeld(Long spaceId, Long messageId, Long sessionId) {
        return isHeld(spaceId, "MESSAGE", messageId) || isHeld(spaceId, "SESSION", sessionId);
    }

    /**
     * 返回指定类型下所有生效中（未解除且未过期）法律保留的资源 ID；类型会先做 trim + 大写规范化。
     */
    public Set<Long> activeResourceIds(Long spaceId, String resourceType) {
        String type = normalizeType(resourceType);
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT resource_id FROM king_ai_legal_hold WHERE space_id = ? "
                        + "AND resource_type = ? AND released_at IS NULL "
                        + "AND (until IS NULL OR until > ?)",
                Long.class, spaceId, type, LocalDateTime.now()));
    }

    private static String normalizeType(String resourceType) {
        return resourceType == null ? "" : resourceType.trim().toUpperCase(Locale.ROOT);
    }
}
