package com.king.ai.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.king.ai.audit.dto.AuditLogView;
import com.king.ai.audit.entity.AuditLog;
import com.king.ai.audit.mapper.AuditLogMapper;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.common.web.RequestIdFilter;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.dto.UserView;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

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
    private final MeterRegistry meterRegistry;
    private final ClientIpResolver clientIpResolver;

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

    /**
     * 历史全局审计清理。合规保留策略启用后由 {@code RetentionJob} 按空间策略接管，
     * 以便逐空间应用保留天数与法律保留；此处随之停用，避免绕过保留。
     */
    public void purgeExpired() {
        int retentionDays = properties.getAudit().getRetentionDays();
        if (!properties.getAudit().isEnabled()
                || properties.getCompliance().getRetention().isEnabled()
                || retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = purgeBefore(null, cutoff, Set.of());
            if (deleted > 0) {
                log.info("Purged {} audit log entries older than {}", deleted, cutoff);
            }
        } catch (RuntimeException ex) {
            // 历史全局清理自行兜底；合规保留路径由 RetentionJob 记录失败审计后上抛。
            log.warn("审计保留清理失败：reason={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * 删除截止时间之前的审计日志，可选按空间限定并排除被法律保留的日志 ID。
     * 返回实际删除行数；审计关闭或参数缺失时不执行删除。删除失败向上抛出，
     * 由调用方记录失败审计，避免把异常误报成“已清理 0 条”。
     */
    public int purgeBefore(Long spaceId, LocalDateTime cutoff, Set<Long> excludedIds) {
        if (!properties.getAudit().isEnabled() || cutoff == null) {
            return 0;
        }
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(spaceId != null, AuditLog::getSpaceId, spaceId)
                .lt(AuditLog::getCreatedAt, cutoff);
        if (excludedIds != null && !excludedIds.isEmpty()) {
            wrapper.notIn(AuditLog::getId, excludedIds);
        }
        return auditLogMapper.delete(wrapper);
    }

    /**
     * 按 ID 删除已通过空间与法律保留校验的审计日志，供保留任务分批调用。
     */
    public int purgeByIds(Long spaceId, Collection<Long> ids) {
        if (!properties.getAudit().isEnabled() || ids == null || ids.isEmpty()) {
            return 0;
        }
        return auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>()
                .eq(spaceId != null, AuditLog::getSpaceId, spaceId)
                .in(AuditLog::getId, ids));
    }

    /**
     * Records an asynchronous chat or tool event where the servlet request context is no longer
     * available. Callers capture the request metadata before handing work to the chat executor.
     */
    public void recordEvent(Long spaceId, Long actorId, String actorName, String action,
                            String resourceType, String resourceId, String status, String detail,
                            String ip, String userAgent, String requestId) {
        persist(spaceId, actorId, actorName, action, resourceType, resourceId, status, detail,
                ip, userAgent, requestId);
    }

    public void recordChat(Long spaceId, Long actorId, String actorName, String action, String resourceId,
                           String status, String detail, String ip, String userAgent, String requestId) {
        recordEvent(spaceId, actorId, actorName, action, "chat", resourceId, status, detail,
                ip, userAgent, requestId);
    }

    private void write(HttpServletRequest request, String action, String resourceType, String resourceId,
                       Long spaceId, String status, Long actorId, String actorName, String detail) {
        persist(spaceId, actorId, actorName, action, resourceType, resourceId, status, detail,
                request == null ? null : clientIpResolver.resolve(request),
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
            // 指标与日志都是失败后的观测手段，任何一环再失败都不能把异常抛回业务请求。
            try {
                meterRegistry.counter("king.ai.audit.write.failures").increment();
            } catch (RuntimeException ignored) {
                // 指标后端不可用时只能放弃这一路信号。
            }
            log.error("审计写入失败：action={}, requestId={}, reason={}",
                    action, requestId, ex.getClass().getSimpleName());
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
