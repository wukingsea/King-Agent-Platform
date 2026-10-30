package com.king.ai.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.license.LicenseService;
import com.king.ai.system.dto.MemberUpdateRequest;
import com.king.ai.system.dto.SpaceCreateRequest;
import com.king.ai.system.dto.SpaceMemberView;
import com.king.ai.system.dto.SpaceUpdateRequest;
import com.king.ai.system.dto.SpaceView;
import com.king.ai.system.entity.Space;
import com.king.ai.system.entity.User;
import com.king.ai.system.entity.UserSpace;
import com.king.ai.system.mapper.SpaceMapper;
import com.king.ai.system.mapper.UserMapper;
import com.king.ai.system.mapper.UserSpaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SpaceService {

    private final SpaceMapper spaceMapper;
    private final UserSpaceMapper userSpaceMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final LicenseService licenseService;

    public List<SpaceView> listFor(UserPrincipal principal) {
        List<Space> spaces;
        if (principal.isAdmin()) {
            spaces = spaceMapper.selectList(new LambdaQueryWrapper<Space>().orderByAsc(Space::getId));
            return spaces.stream().map(space -> view(space, SpaceRole.OWNER.name())).toList();
        }
        List<UserSpace> links = userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                .eq(UserSpace::getUserId, principal.id()));
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, String> roles = links.stream().collect(Collectors.toMap(UserSpace::getSpaceId,
                link -> SpaceRole.parse(link.getRole()).name(), (left, right) -> left));
        spaces = spaceMapper.selectList(new LambdaQueryWrapper<Space>()
                .in(Space::getId, roles.keySet()).orderByAsc(Space::getId));
        return spaces.stream().map(space -> view(space, roles.get(space.getId()))).toList();
    }

    public List<Space> listEntitiesFor(UserPrincipal principal) {
        if (principal.isAdmin()) {
            return spaceMapper.selectList(new LambdaQueryWrapper<Space>().orderByAsc(Space::getId));
        }
        List<Long> ids = userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                        .eq(UserSpace::getUserId, principal.id()))
                .stream().map(UserSpace::getSpaceId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return spaceMapper.selectList(new LambdaQueryWrapper<Space>()
                .in(Space::getId, ids).orderByAsc(Space::getId));
    }

    public Space require(Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少业务空间");
        }
        Space space = spaceMapper.selectById(id);
        if (space == null) {
            throw BizException.notFound("业务空间不存在");
        }
        return space;
    }

    public Long resolveSpaceId(UserPrincipal principal, Long requestedSpaceId) {
        if (requestedSpaceId != null) {
            Space space = require(requestedSpaceId);
            if (space.getStatus() != null && space.getStatus() != 0) {
                throw BizException.forbidden("业务空间已停用");
            }
            if (!principal.isAdmin() && !isMember(principal.id(), requestedSpaceId)) {
                throw BizException.forbidden("无权访问该业务空间");
            }
            return requestedSpaceId;
        }
        return listEntitiesFor(principal).stream()
                .filter(space -> space.getStatus() != null && space.getStatus() == 0)
                .map(Space::getId)
                .findFirst()
                .orElse(null);
    }

    public boolean isMember(Long userId, Long spaceId) {
        return userSpaceMapper.selectCount(new LambdaQueryWrapper<UserSpace>()
                .eq(UserSpace::getUserId, userId)
                .eq(UserSpace::getSpaceId, spaceId)) > 0;
    }

    public SpaceRole roleOf(Long userId, Long spaceId) {
        if (userId == null || spaceId == null) {
            return null;
        }
        UserSpace link = userSpaceMapper.selectOne(new LambdaQueryWrapper<UserSpace>()
                .eq(UserSpace::getUserId, userId)
                .eq(UserSpace::getSpaceId, spaceId)
                .last("LIMIT 1"));
        return link == null ? null : SpaceRole.parse(link.getRole());
    }

    public SpaceRole roleInSpace(UserPrincipal principal, Long spaceId) {
        if (principal.isAdmin()) {
            return SpaceRole.OWNER;
        }
        return roleOf(principal.id(), spaceId);
    }

    public void requireMember(UserPrincipal principal, Long spaceId) {
        requireSpaceRole(principal, spaceId, SpaceRole.VIEWER);
    }

    public void requireSpaceRole(UserPrincipal principal, Long spaceId, SpaceRole required) {
        if (principal.isAdmin()) {
            return;
        }
        SpaceRole role = roleOf(principal.id(), spaceId);
        if (role == null) {
            throw BizException.forbidden("无权访问该业务空间");
        }
        if (!role.satisfies(required)) {
            throw BizException.forbidden("当前空间角色无权执行该操作");
        }
    }

    public void requireAnySpaceRole(UserPrincipal principal, Long spaceId, SpaceRole... allowed) {
        if (principal.isAdmin()) {
            return;
        }
        SpaceRole role = roleOf(principal.id(), spaceId);
        if (role == null) {
            throw BizException.forbidden("无权访问该业务空间");
        }
        for (SpaceRole candidate : allowed) {
            if (role.satisfies(candidate)) {
                return;
            }
        }
        throw BizException.forbidden("当前空间角色无权执行该操作");
    }

    public boolean canViewAllSessions(UserPrincipal principal, Long spaceId) {
        if (principal.isAdmin()) {
            return true;
        }
        SpaceRole role = roleOf(principal.id(), spaceId);
        return role != null && role.satisfies(SpaceRole.AUDITOR);
    }

    public boolean canManageSessions(UserPrincipal principal, Long spaceId) {
        if (principal.isAdmin()) {
            return true;
        }
        SpaceRole role = roleOf(principal.id(), spaceId);
        return role != null && role.atLeast(SpaceRole.ADMIN);
    }

    @Transactional
    public SpaceView create(SpaceCreateRequest request, Long creatorId) {
        licenseService.ensureSpaceCapacity();
        String code = request.code().trim().toLowerCase();
        // Archived spaces keep occupying the unique code, so the check must include them.
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM king_ai_space WHERE code = ?", Long.class, code);
        if (count != null && count > 0) {
            throw BizException.conflict("空间编码已存在（含已归档空间）");
        }
        Space space = new Space();
        space.setName(request.name().trim());
        space.setCode(code);
        space.setDescription(Texts.trimTo(request.description(), 255));
        space.setStatus(request.status() == null ? 0 : request.status());
        spaceMapper.insert(space);
        UserSpace link = new UserSpace();
        link.setUserId(creatorId);
        link.setSpaceId(space.getId());
        link.setRole(SpaceRole.OWNER.name());
        userSpaceMapper.insert(link);
        return view(space, SpaceRole.OWNER.name());
    }

    public SpaceView update(UserPrincipal principal, Long id, SpaceUpdateRequest request) {
        requireSpaceRole(principal, id, SpaceRole.OWNER);
        Space space = require(id);
        space.setName(request.name().trim());
        space.setDescription(Texts.trimTo(request.description(), 255));
        space.setStatus(request.status() == null ? 0 : request.status());
        OptimisticLocks.requireUpdated(spaceMapper.updateById(space), "空间");
        return view(space, roleInSpace(principal, id).name());
    }

    /**
     * Spaces are archived, never removed: sessions, messages, the usage ledger, audit
     * trail and the space's resources stay intact so compliance evidence survives.
     */
    @Transactional
    public void delete(UserPrincipal principal, Long id) {
        requireSpaceRole(principal, id, SpaceRole.OWNER);
        require(id);
        userSpaceMapper.delete(new LambdaQueryWrapper<UserSpace>().eq(UserSpace::getSpaceId, id));
        spaceMapper.softDeleteById(id);
    }

    public List<SpaceMemberView> members(UserPrincipal principal, Long spaceId) {
        requireAnySpaceRole(principal, spaceId, SpaceRole.ADMIN, SpaceRole.AUDITOR);
        require(spaceId);
        List<UserSpace> links = userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                .eq(UserSpace::getSpaceId, spaceId));
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userMapper.selectBatchIds(links.stream().map(UserSpace::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return links.stream()
                .map(link -> {
                    User user = users.get(link.getUserId());
                    return new SpaceMemberView(link.getUserId(),
                            user == null ? "-" : user.getUsername(),
                            user == null ? "-" : user.getNickname(),
                            SpaceRole.parse(link.getRole()).name());
                })
                .sorted(Comparator.comparingInt((SpaceMemberView item) -> roleRank(item.role()))
                        .thenComparing(SpaceMemberView::username))
                .toList();
    }

    public List<Long> memberIds(Long spaceId) {
        require(spaceId);
        return userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                        .eq(UserSpace::getSpaceId, spaceId))
                .stream().map(UserSpace::getUserId).toList();
    }

    @Transactional
    public void replaceMembers(UserPrincipal principal, Long spaceId, MemberUpdateRequest request) {
        requireSpaceRole(principal, spaceId, SpaceRole.ADMIN);
        require(spaceId);
        Map<Long, SpaceRole> incoming = new LinkedHashMap<>();
        if (request.members() != null) {
            for (MemberUpdateRequest.Member item : request.members()) {
                if (item == null || item.userId() == null) {
                    continue;
                }
                if (incoming.put(item.userId(), SpaceRole.parse(item.role())) != null) {
                    throw BizException.badRequest("成员列表中存在重复用户");
                }
            }
        }
        if (incoming.isEmpty()) {
            throw BizException.badRequest("空间至少需要保留一名成员");
        }
        Set<Long> ids = new LinkedHashSet<>(incoming.keySet());
        long exists = userMapper.selectCount(new LambdaQueryWrapper<User>().in(User::getId, ids));
        if (exists != ids.size()) {
            throw BizException.badRequest("包含不存在的用户");
        }
        Map<Long, SpaceRole> existing = userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                        .eq(UserSpace::getSpaceId, spaceId))
                .stream().collect(Collectors.toMap(UserSpace::getUserId,
                        link -> SpaceRole.parse(link.getRole()), (left, right) -> left));
        SpaceRole actorRole = roleInSpace(principal, spaceId);
        if (!principal.isAdmin() && actorRole != SpaceRole.OWNER) {
            for (Map.Entry<Long, SpaceRole> entry : existing.entrySet()) {
                if (entry.getValue() == SpaceRole.OWNER
                        && incoming.getOrDefault(entry.getKey(), null) != SpaceRole.OWNER) {
                    throw BizException.forbidden("只有空间所有者可以调整其他所有者");
                }
            }
            if (incoming.values().stream().anyMatch(role -> role == SpaceRole.OWNER)) {
                throw BizException.forbidden("只有空间所有者可以授予所有者角色");
            }
        }
        if (incoming.values().stream().noneMatch(role -> role == SpaceRole.OWNER)) {
            throw BizException.badRequest("空间至少需要保留一名所有者");
        }
        userSpaceMapper.delete(new LambdaQueryWrapper<UserSpace>().eq(UserSpace::getSpaceId, spaceId));
        for (Map.Entry<Long, SpaceRole> entry : incoming.entrySet()) {
            UserSpace link = new UserSpace();
            link.setUserId(entry.getKey());
            link.setSpaceId(spaceId);
            link.setRole(entry.getValue().name());
            userSpaceMapper.insert(link);
        }
    }

    public List<SpaceView> spacesOfUser(Long userId) {
        List<UserSpace> links = userSpaceMapper.selectList(new LambdaQueryWrapper<UserSpace>()
                .eq(UserSpace::getUserId, userId));
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, String> roles = links.stream().collect(Collectors.toMap(UserSpace::getSpaceId,
                link -> SpaceRole.parse(link.getRole()).name(), (left, right) -> left));
        Map<Long, Space> spaces = new HashMap<>();
        spaceMapper.selectBatchIds(roles.keySet()).forEach(space -> spaces.put(space.getId(), space));
        return spaces.values().stream()
                .sorted(Comparator.comparing(Space::getId))
                .map(space -> view(space, roles.get(space.getId())))
                .toList();
    }

    public SpaceView view(Space space, String role) {
        return new SpaceView(space.getId(), space.getName(), space.getCode(), space.getDescription(),
                space.getStatus(), role, space.getCreatedAt());
    }

    public List<SpaceView> allViews() {
        return spaceMapper.selectList(new LambdaQueryWrapper<Space>().orderByAsc(Space::getId))
                .stream().map(space -> view(space, SpaceRole.OWNER.name())).toList();
    }

    public List<Long> allSpaceIds() {
        return new ArrayList<>(spaceMapper.selectList(new LambdaQueryWrapper<Space>())
                .stream().map(Space::getId).toList());
    }

    private static int roleRank(String role) {
        return switch (SpaceRole.parse(role)) {
            case OWNER -> 0;
            case ADMIN -> 1;
            case EDITOR -> 2;
            case AUDITOR -> 3;
            case VIEWER -> 4;
        };
    }
}
