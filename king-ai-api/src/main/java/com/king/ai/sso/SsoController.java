package com.king.ai.sso;

import com.king.ai.audit.AuditLogService;
import com.king.ai.auth.AuthService;
import com.king.ai.auth.dto.AuthResponse;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.SessionCookies;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.system.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SsoController {

    private final SsoService ssoService;
    private final AuthService authService;
    private final SessionCookies sessionCookies;
    private final AuditLogService auditLogService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/sso/config")
    public ApiResponse<SsoService.SsoConfig> config() {
        return ApiResponse.ok(ssoService.config());
    }

    @GetMapping("/oidc/authorize")
    public void authorize(HttpServletResponse response) throws IOException {
        response.sendRedirect(ssoService.authorizeUrl());
    }

    @GetMapping("/oidc/callback")
    public void callback(@RequestParam(value = "code", required = false) String code,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam(value = "error_description", required = false) String errorDescription,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        try {
            if (errorDescription != null && !errorDescription.isBlank()) {
                throw new com.king.ai.common.BizException(401, errorDescription);
            }
            User user = ssoService.callback(code, state);
            AuthResponse data = authService.completeLogin(user);
            sessionCookies.write(response, data.token());
            auditLogService.loginSucceeded(request, data.user(), data.currentSpaceId());
            response.sendRedirect(ssoService.successRedirect());
        } catch (RuntimeException ex) {
            auditLogService.loginFailed(request, null, ex.getMessage());
            log.warn("OIDC callback failed from {}: {}", clientIpResolver.resolve(request),
                    ex.getMessage());
            response.sendRedirect(ssoService.errorRedirect(ex.getMessage()));
        }
    }
}
