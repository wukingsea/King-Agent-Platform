package com.king.ai.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.king.ai.audit.dto.AuditLogView;
import com.king.ai.audit.entity.AuditLog;
import com.king.ai.audit.mapper.AuditLogMapper;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.common.web.RequestIdFilter;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.dto.UserView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_FAILURE = "FAILURE";

    private static final String ACTOR_USER = "USER";

    private final AuditLogMapper auditLogMapper;
    private final KingAiProperties properties;

    public void recordRequest(HttpServletRequest request, Object handler, UserPrincipal principal,
                              String status, String detail) {
        if (!properties.getAudit().isEnabled()
                || principal == null
                || !(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        String resourceType = resourceOf(handlerMethod);
        String action = resourceType + "." + camelToSnake(handlerMethod.getMethod().getName());
        write(request, action, resourceType, resourceIdOf(request), principal.spaceId(),
                status, principal.id(), principal.username(), detail);
    }

    public void recordDenied(HttpServletRequest request, Object handler, Long actorId, String actorName,
                             Long spaceId, String reason) {
        if (!properties.getAudit().isEnabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        String resourceType = resourceOf(handlerMethod);
        String action = resourceType + "." + camelToSnake(handlerMethod.getMethod().getName());
        write(request, action, resourceType, resourceIdOf(request), spaceId,
                STATUS_DENIED, actorId, actorName, reason);
    }

    public void loginSucceeded(HttpServletRequest request, UserView user, Long spaceId) {
        write(request, "auth.login", "auth", null, spaceId, STATUS_SUCCESS,
                user == null ? null : user.id(), user == null ? null : user.username(), "登录成功");
    }

    public void loginFailed(HttpServletRequest request, String username, String reason) {
        write(request, "auth.login", "auth", null, null, STATUS_FAILURE,
                null, username, Texts.blankToDefault(reason, "登录失败"));
    }

    public PageResult<AuditLogView> page(Long spaceId, Long actorId, String action, String status,
                                         LocalDateTime from, LocalDateTime to, int page, int size) {
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        String normalizedStatus = Texts.isBlank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String actionPrefix = Texts.isBlank(action) ? null : action.trim();
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(spaceId != null, AuditLog::getSpaceId, spaceId)
                .eq(actorId != null, AuditLog::getActorId, actorId)
                .eq(normalizedStatus != null, AuditLog::getStatus, normalizedStatus)
                .likeRight(actionPrefix != null, AuditLog::getAction, actionPrefix)
                .ge(from != null, AuditLog::getCreatedAt, from)
                .le(to != null, AuditLog::getCreatedAt, to)
                .orderByDesc(AuditLog::getId);
        Page<AuditLog> result = auditLogMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResult.of(result.getRecords().stream().map(AuditLogView::of).toList(),
                result.getTotal(), pageNo, pageSize);
    }

    @Scheduled(cron = "${king-ai.audit.retention-cron:0 30 3 * * *}")
    public void purgeExpired() {
        int retentionDays = properties.getAudit().getRetentionDays();
        if (!properties.getAudit().isEnabled() || retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>()
                    .lt(AuditLog::getCreatedAt, cutoff));
            if (deleted > 0) {
                log.info("Purged {} audit log entries older than {}", deleted, cutoff);
            }
        } catch (RuntimeException ex) {
            log.warn("Audit log retention purge failed: {}", ex.getMessage());
        }
    }

    /**
     * Records an asynchronous chat or tool event where the servlet request context is no longer
     * available. Callers capture the request metadata before handing work to the chat executor.
     */
    public void recordChat(Long spaceId, Long actorId, String actorName, String action, String resourceId,
                           String status, String detail, String ip, String userAgent, String requestId) {
        persist(spaceId, actorId, actorName, action, "chat", resourceId, status, detail, ip, userAgent,
                requestId);
    }

    private void write(HttpServletRequest request, String action, String resourceType, String resourceId,
                       Long spaceId, String status, Long actorId, String actorName, String detail) {
        persist(spaceId, actorId, actorName, action, resourceType, resourceId, status, detail,
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : request.getHeader("User-Agent"),
                request == null ? null : requestId(request));
    }

    private void persist(Long spaceId, Long actorId, String actorName, String action, String resourceType,
                         String resourceId, String status, String detail, String ip, String userAgent,
                         String requestId) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }
        try {
            AuditLog entry = new AuditLog();
            entry.setSpaceId(spaceId);
            entry.setActorType(ACTOR_USER);
            entry.setActorId(actorId);
            entry.setActorName(Texts.trimTo(actorName, 100));
            entry.setAction(Texts.trimTo(action, 120));
            entry.setResourceType(Texts.trimTo(resourceType, 60));
            entry.setResourceId(Texts.trimTo(resourceId, 64));
            entry.setStatus(status);
            entry.setDetail(Texts.oneLine(detail, 1000));
            entry.setIp(Texts.trimTo(ip, 64));
            entry.setUserAgent(Texts.trimTo(userAgent, 255));
            entry.setRequestId(Texts.trimTo(requestId, 64));
            entry.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist audit log ({}): {}", action, ex.getMessage());
        }
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? null : Texts.trimTo(String.valueOf(value), 64);
    }

    private static String resourceIdOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attribute instanceof java.util.Map<?, ?> variables) {
            for (String key : new String[]{"id", "docId", "sessionId", "skillId"}) {
                Object value = variables.get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        return null;
    }

    private static String resourceOf(HandlerMethod handlerMethod) {
        String bean = handlerMethod.getBeanType().getSimpleName();
        if (bean.endsWith("Controller")) {
            bean = bean.substring(0, bean.length() - "Controller".length());
        }
        return camelToSnake(bean);
    }

    private static String camelToSnake(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
