package com.king.ai.audit;

import com.king.ai.common.Texts;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class AuditLogInterceptor implements AsyncHandlerInterceptor {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/mfa/verify",
            "/api/auth/oidc/",
            "/api/public/",
            "/api/demo/",
            "/api/chat/",
            "/api/health"
    );

    private final AuditLogService auditLogService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod) || isExcluded(request) || !isMutating(request.getMethod())) {
            return;
        }
        UserPrincipal principal = RequestContext.get();
        if (principal == null) {
            return;
        }
        int statusCode = response.getStatus();
        String status;
        if (statusCode < 400) {
            status = AuditLogService.STATUS_SUCCESS;
        } else if (statusCode == 401 || statusCode == 403 || statusCode == 428) {
            status = AuditLogService.STATUS_DENIED;
        } else {
            status = AuditLogService.STATUS_FAILURE;
        }
        String detail = request.getMethod() + " " + request.getRequestURI() + " -> " + statusCode;
        if (ex != null) {
            detail += " (" + Texts.oneLine(ex.getMessage(), 300) + ")";
        }
        auditLogService.recordRequest(request, handler, principal, status, detail);
    }

    private boolean isExcluded(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String prefix : EXCLUDED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }
}
