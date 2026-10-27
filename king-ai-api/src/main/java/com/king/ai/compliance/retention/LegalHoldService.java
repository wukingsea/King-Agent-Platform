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

    public boolean isHeld(Long spaceId, String resourceType, Long resourceId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<LegalHold>()
                .eq(LegalHold::getSpaceId, spaceId)
                .eq(LegalHold::getResourceType, resourceType)
                .eq(LegalHold::getResourceId, resourceId)
                .isNull(LegalHold::getReleasedAt)
                .and(w -> w.isNull(LegalHold::getUntil)
                        .or().gt(LegalHold::getUntil, LocalDateTime.now())));
        return count != null && count > 0;
    }

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

    public boolean isMessageHeld(Long spaceId, Long messageId, Long sessionId) {
        return isHeld(spaceId, "MESSAGE", messageId) || isHeld(spaceId, "SESSION", sessionId);
    }

    public Set<Long> activeResourceIds(Long spaceId, String resourceType) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT resource_id FROM king_ai_legal_hold WHERE space_id = ? "
                        + "AND resource_type = ? AND released_at IS NULL "
                        + "AND (until IS NULL OR until > ?)",
                Long.class, spaceId, resourceType, LocalDateTime.now()));
    }
}
