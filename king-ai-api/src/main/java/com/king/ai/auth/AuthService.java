package com.king.ai.auth;

import com.king.ai.auth.dto.AuthResponse;
import com.king.ai.auth.dto.ChangePasswordRequest;
import com.king.ai.auth.dto.LoginRequest;
import com.king.ai.common.BizException;
import com.king.ai.common.Ids;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.UserService;
import com.king.ai.system.dto.SpaceView;
import com.king.ai.system.entity.User;
import com.king.ai.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final LoginAttemptGuard attemptGuard;
    private final KingAiProperties properties;

    public AuthResponse login(LoginRequest request, String clientIp) {
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
        String token = Ids.token();
        tokenStore.save(token, user.getId(), properties.getSessionTtl());
        List<SpaceView> spaces = userService.spacesFor(user);
        Long currentSpaceId = spaces.stream()
                .filter(space -> space.status() != null && space.status() == 0)
                .map(SpaceView::id)
                .findFirst()
                .orElse(null);
        return new AuthResponse(token, userService.view(user), spaces, currentSpaceId);
    }

    public AuthResponse me() {
        UserPrincipal principal = RequestContext.require();
        User user = userService.require(principal.id());
        List<SpaceView> spaces = userService.spacesFor(user);
        return new AuthResponse(null, userService.view(user), spaces, principal.spaceId());
    }

    public void logout(String token) {
        if (!com.king.ai.common.Texts.isBlank(token)) {
            tokenStore.delete(token);
        }
    }

    public void changePassword(ChangePasswordRequest request) {
        UserPrincipal principal = RequestContext.require();
        User user = userService.require(principal.id());
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw BizException.badRequest("原密码不正确");
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(0);
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        tokenStore.deleteByUserId(user.getId());
    }
}
