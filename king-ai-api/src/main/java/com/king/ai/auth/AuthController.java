package com.king.ai.auth;

import com.king.ai.audit.AuditLogService;
import com.king.ai.auth.dto.AuthResponse;
import com.king.ai.auth.dto.ChangePasswordRequest;
import com.king.ai.auth.dto.LoginRequest;
import com.king.ai.auth.dto.MfaDisableRequest;
import com.king.ai.auth.dto.MfaEnableRequest;
import com.king.ai.auth.dto.MfaEnableResponse;
import com.king.ai.auth.dto.MfaSetupRequest;
import com.king.ai.auth.dto.MfaSetupResponse;
import com.king.ai.auth.dto.MfaStatusView;
import com.king.ai.auth.dto.MfaVerifyRequest;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
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
    private final MfaService mfaService;
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
        if (data.mfaRequired()) {
            return ApiResponse.ok(data);
        }
        sessionCookies.write(response, data.token());
        auditLogService.loginSucceeded(servletRequest, data.user(), data.currentSpaceId());
        return ApiResponse.ok(data);
    }

    @PostMapping("/mfa/verify")
    public ApiResponse<AuthResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse response) {
        AuthResponse data;
        try {
            data = authService.verifyMfa(request, clientIpResolver.resolve(servletRequest));
        } catch (RuntimeException ex) {
            auditLogService.loginFailed(servletRequest, null, ex.getMessage());
            throw ex;
        }
        sessionCookies.write(response, data.token());
        auditLogService.loginSucceeded(servletRequest, data.user(), data.currentSpaceId());
        return ApiResponse.ok(data);
    }

    @GetMapping("/mfa")
    public ApiResponse<MfaStatusView> mfaStatus() {
        return ApiResponse.ok(mfaService.status(RequestContext.require()));
    }

    @PostMapping("/mfa/setup")
    public ApiResponse<MfaSetupResponse> setupMfa(@Valid @RequestBody MfaSetupRequest request) {
        return ApiResponse.ok(mfaService.setup(RequestContext.require(), request.password()));
    }

    @PostMapping("/mfa/enable")
    public ApiResponse<MfaEnableResponse> enableMfa(@Valid @RequestBody MfaEnableRequest request) {
        return ApiResponse.ok(mfaService.enable(RequestContext.require(), request.code()));
    }

    @PostMapping("/mfa/disable")
    public ApiResponse<Void> disableMfa(@Valid @RequestBody MfaDisableRequest request) {
        mfaService.disable(RequestContext.require(), request.password(), request.code());
        return ApiResponse.ok();
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
