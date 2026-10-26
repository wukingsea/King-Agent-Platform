package com.king.ai.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.auth.BearerTokens;
import com.king.ai.auth.TokenStore;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.SpaceService;
import com.king.ai.system.entity.User;
import com.king.ai.system.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements AsyncHandlerInterceptor {

    private static final Set<String> PASSWORD_CHANGE_ALLOWED_PATHS = Set.of(
            "/api/auth/me",
            "/api/auth/password",
            "/api/auth/logout"
    );

    private final TokenStore tokenStore;
    private final UserMapper userMapper;
    private final SpaceService spaceService;
    private final SessionCookies sessionCookies;
    private final KingAiProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = BearerTokens.extract(request.getHeader("Authorization"));
        boolean cookieAuth = false;
        if (token == null) {
            token = sessionCookies.find(request);
            cookieAuth = token != null;
        }
        if (token == null) {
            writeError(response, 401, "请先登录");
            return false;
        }
        if (cookieAuth && !isSafeMethod(request.getMethod()) && !validOrigin(request)) {
            auditLogService.recordDenied(request, handler, null, null, null, "请求来源校验失败");
            writeError(response, 403, "请求来源校验失败，请从平台页面重新操作");
            return false;
        }
        try {
            Long userId = tokenStore.findUserId(token);
            if (userId == null) {
                writeError(response, 401, "登录已过期，请重新登录");
                return false;
            }
            User user = userMapper.selectById(userId);
            if (user == null || (user.getStatus() != null && user.getStatus() != 0)) {
                auditLogService.recordDenied(request, handler, userId, null, null, "账号不可用");
                writeError(response, 401, "账号不可用，请联系管理员");
                return false;
            }
            boolean mustChangePassword = user.getMustChangePassword() != null
                    && user.getMustChangePassword() == 1;
            if (mustChangePassword && !PASSWORD_CHANGE_ALLOWED_PATHS.contains(request.getRequestURI())) {
                auditLogService.recordDenied(request, handler, user.getId(), user.getUsername(), null,
                        "未修改初始密码");
                writeError(response, 428, "请先修改初始密码");
                return false;
            }

            UserPrincipal provisional = new UserPrincipal(user.getId(), user.getUsername(), user.getNickname(),
                    user.getRole(), null, null, mustChangePassword);
            Long requestedSpaceId = parseSpaceId(request);
            Long spaceId = spaceService.resolveSpaceId(provisional, requestedSpaceId);
            SpaceRole spaceRole = spaceService.roleOf(user.getId(), spaceId);
            UserPrincipal principal = new UserPrincipal(user.getId(), user.getUsername(), user.getNickname(),
                    user.getRole(), spaceId, spaceRole == null ? null : spaceRole.name(), mustChangePassword);

            if (handler instanceof HandlerMethod handlerMethod) {
                if (requiresAdmin(handlerMethod) && !principal.isAdmin()) {
                    auditLogService.recordDenied(request, handler, user.getId(), user.getUsername(), spaceId,
                            "需要管理员权限");
                    writeError(response, 403, "需要管理员权限");
                    return false;
                }
                SpaceRole[] requiredRoles = requiredSpaceRoles(handlerMethod);
                if (requiredRoles != null && requiredRoles.length > 0
                        && !principal.hasAnySpaceRole(requiredRoles)) {
                    auditLogService.recordDenied(request, handler, user.getId(), user.getUsername(), spaceId,
                            "当前空间角色无权执行该操作");
                    writeError(response, 403, "当前空间角色无权执行该操作");
                    return false;
                }
            }
            RequestContext.set(principal);
            return true;
        } catch (BizException ex) {
            writeError(response, ex.getCode(), ex.getMessage());
            return false;
        } catch (DataAccessException ex) {
            log.warn("Authentication dependency unavailable: {}", ex.getMessage());
            writeError(response, 503, "认证服务暂时不可用，请稍后重试");
            return false;
        } catch (Exception ex) {
            log.error("Authentication failed", ex);
            writeError(response, 500, "认证服务异常");
            return false;
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        RequestContext.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }

    private boolean requiresAdmin(HandlerMethod handlerMethod) {
        return handlerMethod.getMethodAnnotation(RequireAdmin.class) != null
                || handlerMethod.getBeanType().isAnnotationPresent(RequireAdmin.class);
    }

    private SpaceRole[] requiredSpaceRoles(HandlerMethod handlerMethod) {
        RequireSpaceRole annotation = handlerMethod.getMethodAnnotation(RequireSpaceRole.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireSpaceRole.class);
        }
        return annotation == null ? null : annotation.value();
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    private boolean validOrigin(HttpServletRequest request) {
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank() || "null".equalsIgnoreCase(source.trim())) {
            return false;
        }
        String normalized = normalizeOrigin(source);
        java.util.List<String> allowed = properties.getSecurity().getAllowedOrigins();
        if (allowed != null) {
            boolean hasAllowlist = false;
            for (String origin : allowed) {
                if (origin != null && !origin.isBlank()) {
                    hasAllowlist = true;
                    if (normalizeOrigin(origin).equalsIgnoreCase(normalized)) {
                        return true;
                    }
                }
            }
            if (hasAllowlist) {
                return false;
            }
        }
        try {
            java.net.URI uri = java.net.URI.create(normalized);
            String host = uri.getHost();
            String requestHost = request.getServerName();
            return host != null && requestHost != null && host.equalsIgnoreCase(requestHost);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String normalizeOrigin(String origin) {
        String value = origin.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private Long parseSpaceId(HttpServletRequest request) {
        String header = request.getHeader("X-Space-Id");
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(header.trim());
            if (value <= 0) {
                throw new NumberFormatException("invalid");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw BizException.badRequest("X-Space-Id 不合法");
        }
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
