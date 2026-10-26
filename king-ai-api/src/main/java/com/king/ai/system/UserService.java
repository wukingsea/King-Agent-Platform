package com.king.ai.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.king.ai.auth.TokenStore;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.system.dto.SpaceView;
import com.king.ai.system.dto.UserCreateRequest;
import com.king.ai.system.dto.UserUpdateRequest;
import com.king.ai.system.dto.UserView;
import com.king.ai.system.entity.User;
import com.king.ai.system.entity.UserSpace;
import com.king.ai.system.mapper.UserMapper;
import com.king.ai.system.mapper.UserSpaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserSpaceMapper userSpaceMapper;
    private final SpaceService spaceService;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;

    public User require(Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少用户 ID");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    public User findByUsername(String username) {
        if (Texts.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username.trim().toLowerCase()));
    }

    public PageResult<UserView> page(int pageNo, int size, String keyword) {
        Page<User> page = new Page<>(pageNo, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getNickname, kw)
                    .or().like(User::getEmail, kw));
        }
        wrapper.orderByAsc(User::getId);
        Page<User> result = userMapper.selectPage(page, wrapper);
        List<UserView> views = result.getRecords().stream().map(this::view).toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    public UserView view(User user) {
        List<Long> spaceIds = userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                        .eq(UserSpace::getUserId, user.getId()))
                .stream().map(UserSpace::getSpaceId).sorted().toList();
        return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getRole(), user.getStatus(), spaceIds,
                user.getMustChangePassword() == null ? 0 : user.getMustChangePassword(), user.getCreatedAt());
    }

    public List<SpaceView> spacesFor(User user) {
        if ("ADMIN".equals(user.getRole())) {
            return spaceService.allViews();
        }
        return spaceService.spacesOfUser(user.getId());
    }

    @Transactional
    public UserView create(UserCreateRequest request) {
        String username = request.username().trim().toLowerCase();
        if (findByUsername(username) != null) {
            throw BizException.conflict("账号已存在");
        }
        validateSpaces(request.spaceIds());
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(Texts.blankToDefault(request.nickname(), username));
        user.setEmail(Texts.trimTo(request.email(), 100));
        user.setRole(request.role());
        user.setStatus(request.status());
        user.setMustChangePassword(1);
        userMapper.insert(user);
        replaceSpaces(user.getId(), request.spaceIds());
        return view(user);
    }

    @Transactional
    public UserView update(Long id, UserUpdateRequest request, Long operatorId) {
        User user = require(id);
        if (id.equals(operatorId)) {
            if (request.status() != 0) {
                throw BizException.badRequest("不能停用当前登录账号");
            }
            if (!"ADMIN".equals(request.role())) {
                throw BizException.badRequest("不能降级当前登录账号");
            }
        }
        if ("ADMIN".equals(user.getRole()) && (!"ADMIN".equals(request.role()) || request.status() != 0)) {
            ensureOtherActiveAdmin(id);
        }
        validateSpaces(request.spaceIds());
        user.setNickname(Texts.blankToDefault(request.nickname(), user.getUsername()));
        user.setEmail(Texts.trimTo(request.email(), 100));
        user.setRole(request.role());
        user.setStatus(request.status());
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        replaceSpaces(id, request.spaceIds());
        return view(user);
    }

    public void resetPassword(Long id, String newPassword) {
        User user = require(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(1);
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        tokenStore.deleteByUserId(id);
    }

    @Transactional
    public void delete(Long id, Long operatorId) {
        if (id.equals(operatorId)) {
            throw BizException.badRequest("不能删除当前登录账号");
        }
        User user = require(id);
        if ("ADMIN".equals(user.getRole())) {
            ensureOtherActiveAdmin(id);
        }
        userSpaceMapper.delete(new LambdaQueryWrapper<UserSpace>().eq(UserSpace::getUserId, id));
        userMapper.deleteById(id);
        tokenStore.deleteByUserId(id);
    }

    public List<com.king.ai.system.dto.UserOption> options() {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .orderByAsc(User::getUsername))
                .stream()
                .map(user -> new com.king.ai.system.dto.UserOption(user.getId(), user.getUsername(),
                        user.getNickname(), user.getRole(), user.getStatus()))
                .toList();
    }

    private void ensureOtherActiveAdmin(Long excludedId) {
        long activeAdmins = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, "ADMIN")
                .eq(User::getStatus, 0)
                .ne(User::getId, excludedId));
        if (activeAdmins == 0) {
            throw BizException.badRequest("系统至少需要保留一个启用的管理员账号");
        }
    }

    private void validateSpaces(List<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return;
        }
        Set<Long> ids = new java.util.LinkedHashSet<>(spaceIds);
        ids.remove(null);
        for (Long spaceId : ids) {
            spaceService.require(spaceId);
        }
    }

    private void replaceSpaces(Long userId, List<Long> spaceIds) {
        java.util.Map<Long, SpaceRole> existing = userSpaceMapper.selectList(
                        new LambdaQueryWrapper<UserSpace>().eq(UserSpace::getUserId, userId))
                .stream().collect(java.util.stream.Collectors.toMap(UserSpace::getSpaceId,
                        link -> SpaceRole.parse(link.getRole()), (left, right) -> left));
        Set<Long> target = spaceIds == null ? Set.of() : new java.util.LinkedHashSet<>(
                spaceIds.stream().filter(java.util.Objects::nonNull).toList());
        for (java.util.Map.Entry<Long, SpaceRole> entry : existing.entrySet()) {
            if (entry.getValue() == SpaceRole.OWNER && !target.contains(entry.getKey())) {
                long otherOwners = userSpaceMapper.selectCount(new LambdaQueryWrapper<UserSpace>()
                        .eq(UserSpace::getSpaceId, entry.getKey())
                        .eq(UserSpace::getRole, SpaceRole.OWNER.name())
                        .ne(UserSpace::getUserId, userId));
                if (otherOwners == 0) {
                    throw BizException.badRequest("该用户是空间唯一所有者，请先在空间成员中转移所有权");
                }
            }
        }
        userSpaceMapper.delete(new LambdaQueryWrapper<UserSpace>().eq(UserSpace::getUserId, userId));
        for (Long spaceId : target) {
            UserSpace link = new UserSpace();
            link.setUserId(userId);
            link.setSpaceId(spaceId);
            link.setRole(existing.getOrDefault(spaceId, SpaceRole.VIEWER).name());
            userSpaceMapper.insert(link);
        }
    }

    public UserPrincipal principal(User user, Long spaceId) {
        SpaceRole role = spaceService.roleOf(user.getId(), spaceId);
        return new UserPrincipal(user.getId(), user.getUsername(), user.getNickname(), user.getRole(), spaceId,
                role == null ? null : role.name(), user.getMustChangePassword() != null
                && user.getMustChangePassword() == 1);
    }

    public List<UserView> viewsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .sorted(Comparator.comparing(User::getUsername))
                .map(this::view)
                .toList();
    }
}
