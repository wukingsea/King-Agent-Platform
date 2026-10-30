package com.king.ai.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.king.ai.auth.dto.AuthResponse;
import com.king.ai.auth.dto.ChangePasswordRequest;
import com.king.ai.auth.dto.LoginRequest;
import com.king.ai.auth.dto.LoginSessionView;
import com.king.ai.auth.dto.MfaVerifyRequest;
import com.king.ai.common.BizException;
import com.king.ai.common.Ids;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.UserService;
import com.king.ai.system.dto.SpaceView;
import com.king.ai.system.entity.User;
import com.king.ai.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final LoginAttemptGuard attemptGuard;
    private final KingAiProperties properties;
    private final MfaChallengeService mfaChallengeService;
    private final MfaService mfaService;
    private final PasswordHistoryService passwordHistoryService;

    public AuthResponse login(LoginRequest request, String clientIp, String userAgent) {
        String username = request.username().trim().toLowerCase();
        attemptGuard.check(username, clientIp);
        User user = userService.findByUsername(username);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            attemptGuard.fail(username, clientIp);
            throw BizException.unauthorized("账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 0) {
            throw BizException.forbidden("账号已停用，请联系管理员");
        }
        attemptGuard.success(username, clientIp);
        if (mfaRequired(user)) {
            return new AuthResponse(null, null, List.of(), null, true, mfaChallengeService.issue(user.getId()));
        }
        return completeLogin(user, clientIp, userAgent);
    }

    /**
     * Completes a login after the password (and, when enabled, the second factor) has been
     * verified. Shared with the OIDC callback so both flows issue identical sessions.
     */
    public AuthResponse completeLogin(User user, String clientIp, String userAgent) {
        try {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, user.getId())
                    .set(User::getLastLoginAt, LocalDateTime.now()));
        } catch (RuntimeException ex) {
            log.warn("Failed to record last login time: {}", ex.getClass().getSimpleName());
        }
        String token = Ids.token();
        tokenStore.save(token, user.getId(), properties.getSessionTtl(), clientIp, userAgent);
        List<SpaceView> spaces = userService.spacesFor(user);
        Long currentSpaceId = spaces.stream()
                .filter(space -> space.status() != null && space.status() == 0)
                .map(SpaceView::id)
                .findFirst()
                .orElse(null);
        return new AuthResponse(token, userService.view(user), spaces, currentSpaceId);
    }

    public AuthResponse verifyMfa(MfaVerifyRequest request, String clientIp, String userAgent) {
        Long userId = mfaChallengeService.verify(request.challenge());
        User user = userService.require(userId);
        if (user.getStatus() != null && user.getStatus() != 0) {
            throw BizException.forbidden("账号已停用，请联系管理员");
        }
        if (!mfaRequired(user)) {
            throw BizException.badRequest("该账号未启用二次验证，请直接登录");
        }
        if (!mfaService.verifyForLogin(user, request.code())) {
            attemptGuard.fail(user.getUsername(), clientIp);
            throw BizException.unauthorized("验证码不正确");
        }
        attemptGuard.success(user.getUsername(), clientIp);
        return completeLogin(user, clientIp, userAgent);
    }

    private boolean mfaRequired(User user) {
        return properties.getMfa().isEnabled() && user.getMfaEnabled() != null && user.getMfaEnabled() == 1;
    }

    public AuthResponse me() {
        UserPrincipal principal = RequestContext.require();
        User user = userService.require(principal.id());
        List<SpaceView> spaces = userService.spacesFor(user);
        return new AuthResponse(null, userService.view(user), spaces, principal.spaceId());
    }

    public void logout(String token) {
        if (!Texts.isBlank(token)) {
            tokenStore.delete(token);
        }
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        UserPrincipal principal = RequestContext.require();
        User user = userService.require(principal.id());
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw BizException.badRequest("原密码不正确");
        }
        PasswordRules.validate(request.newPassword());
        if (request.oldPassword().equals(request.newPassword())) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        passwordHistoryService.ensureNotReused(user.getId(), request.newPassword());
        String encoded = passwordEncoder.encode(request.newPassword());
        user.setPassword(encoded);
        user.setMustChangePassword(0);
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        passwordHistoryService.record(user.getId(), encoded);
        tokenStore.deleteByUserId(user.getId());
    }

    public List<LoginSessionView> sessions(String currentToken) {
        return tokenStore.sessions(RequestContext.require().id(), currentToken);
    }

    public int logoutOtherSessions(String currentToken) {
        if (Texts.isBlank(currentToken)) {
            throw BizException.badRequest("缺少当前会话标识");
        }
        return tokenStore.deleteOthers(RequestContext.require().id(), currentToken);
    }

    public void logoutAll() {
        UserPrincipal principal = RequestContext.require();
        tokenStore.deleteByUserId(principal.id());
    }
}
