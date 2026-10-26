package com.king.ai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.auth.TokenStore;
import com.king.ai.system.entity.Space;
import com.king.ai.system.entity.User;
import com.king.ai.system.entity.UserSpace;
import com.king.ai.system.mapper.SpaceMapper;
import com.king.ai.system.mapper.UserMapper;
import com.king.ai.system.mapper.UserSpaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final String DEMO_ADMIN_HASH =
            "$2b$10$lsmOp6EiL1o1seX6V8cfFemiU4lY/j40roaUqVMb.iJGzG2UufCMe";
    private static final String DEMO_ADMIN_USERNAME = "admin";
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{12,72}$");
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "admin123", "admin1234", "password123", "123456789012", "changeme1234"
    );

    private final UserMapper userMapper;
    private final SpaceMapper spaceMapper;
    private final UserSpaceMapper userSpaceMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final KingAiProperties properties;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long adminCount = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, "ADMIN"));
        if (adminCount != null && adminCount > 0) {
            rotateSeededDemoAdmin();
            return;
        }

        KingAiProperties.Admin config = properties.getAdmin();
        String username = trimToNull(config.getUsername());
        if (username == null) {
            throw new IllegalStateException(
                    "未检测到系统管理员，请通过 KING_AI_ADMIN_USERNAME 配置初始管理员账号");
        }
        String password = requireInitialPassword();
        User existing = findByUsername(username);
        if (existing != null) {
            throw new IllegalStateException("账号 " + username + " 已存在但不是系统管理员，请更换 KING_AI_ADMIN_USERNAME");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(trimToNull(config.getNickname()) == null ? username : config.getNickname().trim());
        user.setRole("ADMIN");
        user.setStatus(0);
        user.setMustChangePassword(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            User concurrent = findByUsername(username);
            if (concurrent == null || !"ADMIN".equalsIgnoreCase(concurrent.getRole())) {
                throw ex;
            }
            return;
        }

        Space space = ensureSpace(config);
        ensureOwner(user, space);
        log.info("Bootstrapped initial administrator '{}' in space '{}'; a password change is required at first login",
                username, space.getCode());
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private String requireInitialPassword() {
        String password = properties.getAdmin().getPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "未检测到可用管理员，请通过 KING_AI_ADMIN_PASSWORD 配置初始密码（至少 12 位且包含字母和数字）");
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()
                || WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "KING_AI_ADMIN_PASSWORD 强度不足：需 12-72 位且同时包含字母和数字，并避免常见弱口令");
        }
        return password;
    }

    private void rotateSeededDemoAdmin() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        User seeded = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, DEMO_ADMIN_USERNAME)
                .eq(User::getPassword, DEMO_ADMIN_HASH));
        if (seeded == null) {
            return;
        }
        String password = requireInitialPassword();
        seeded.setPassword(passwordEncoder.encode(password));
        seeded.setMustChangePassword(1);
        userMapper.updateById(seeded);
        tokenStore.deleteByUserId(seeded.getId());
        log.warn("Rotated the legacy demo administrator password; a password change is required at next login");
    }

    private Space ensureSpace(KingAiProperties.Admin config) {
        String code = trimToNull(config.getSpaceCode());
        if (code == null) {
            code = "default";
        }
        Space space = spaceMapper.selectOne(new LambdaQueryWrapper<Space>().eq(Space::getCode, code));
        if (space != null) {
            return space;
        }
        space = new Space();
        space.setName(trimToNull(config.getSpaceName()) == null ? "默认空间" : config.getSpaceName().trim());
        space.setCode(code);
        space.setDescription("系统初始化空间");
        space.setStatus(0);
        try {
            spaceMapper.insert(space);
            return space;
        } catch (DuplicateKeyException ex) {
            Space concurrent = spaceMapper.selectOne(new LambdaQueryWrapper<Space>().eq(Space::getCode, code));
            if (concurrent == null) {
                throw ex;
            }
            return concurrent;
        }
    }

    private void ensureOwner(User user, Space space) {
        Long links = userSpaceMapper.selectCount(new LambdaQueryWrapper<UserSpace>()
                .eq(UserSpace::getUserId, user.getId())
                .eq(UserSpace::getSpaceId, space.getId()));
        if (links != null && links > 0) {
            return;
        }
        UserSpace link = new UserSpace();
        link.setUserId(user.getId());
        link.setSpaceId(space.getId());
        link.setRole("OWNER");
        try {
            userSpaceMapper.insert(link);
        } catch (DuplicateKeyException ignored) {
            log.debug("Initial administrator membership already exists");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
