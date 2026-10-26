package com.king.ai.auth;

import com.king.ai.audit.AuditLogService;
import com.king.ai.auth.dto.AuthResponse;
import com.king.ai.auth.dto.ChangePasswordRequest;
import com.king.ai.auth.dto.LoginRequest;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.SessionCookies;
import com.king.ai.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionCookies sessionCookies;
    private final AuditLogService auditLogService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest servletRequest,
                                           HttpServletResponse response) {
        AuthResponse data;
        try {
            data = authService.login(request, clientIpResolver.resolve(servletRequest));
        } catch (RuntimeException ex) {
            auditLogService.loginFailed(servletRequest, request.username(), ex.getMessage());
            throw ex;
        }
        sessionCookies.write(response, data.token());
        auditLogService.loginSucceeded(servletRequest, data.user(), data.currentSpaceId());
        return ApiResponse.ok(data);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        String token = BearerTokens.extract(authorization);
        if (token == null) {
            token = sessionCookies.find(request);
        }
        authService.logout(token);
        sessionCookies.clear(response);
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<AuthResponse> me() {
        return ApiResponse.ok(authService.me());
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletResponse response) {
        authService.changePassword(request);
        sessionCookies.clear(response);
        return ApiResponse.ok();
    }
}
