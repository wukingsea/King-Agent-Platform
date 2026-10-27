package com.king.ai.auth;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.king.ai.auth.dto.AuthResponse;
import com.king.ai.auth.dto.LoginRequest;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.UserService;
import com.king.ai.system.dto.UserView;
import com.king.ai.system.entity.User;
import com.king.ai.system.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    static {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    private UserMapper userMapper;
    private UserService userService;
    private PasswordEncoder passwordEncoder;
    private TokenStore tokenStore;
    private LoginAttemptGuard attemptGuard;
    private KingAiProperties properties;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = mock(UserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenStore = mock(TokenStore.class);
        attemptGuard = mock(LoginAttemptGuard.class);
        properties = mock(KingAiProperties.class);
        service = new AuthService(userMapper, userService, passwordEncoder, tokenStore, attemptGuard, properties);
    }

    @Test
    void loginRecordsLastLoginAtWithNarrowUpdate() {
        User user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(properties.getSessionTtl()).thenReturn(Duration.ofDays(1));
        when(userService.spacesFor(user)).thenReturn(List.of());
        when(userService.view(user)).thenReturn(new UserView(7L, "alice", "Alice", "alice@example.com",
                "USER", 0, List.of(), 0, LocalDateTime.now()));

        AuthResponse response = service.login(new LoginRequest("Alice ", "pw"), "1.2.3.4");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<User>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(userMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet())
                .contains("last_login_at")
                .doesNotContain("password", "status", "role", "nickname");
        assertThat(captor.getValue().getSqlSegment()).contains("id");
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void lastLoginAtIsNeverWrittenByFullEntityUpdates() throws Exception {
        com.baomidou.mybatisplus.annotation.TableField field = User.class
                .getDeclaredField("lastLoginAt")
                .getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);

        assertThat(field).isNotNull();
        assertThat(field.updateStrategy())
                .isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER);
    }

    @Test
    void loginSucceedsWhenRecordingLastLoginFails() {
        User user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(properties.getSessionTtl()).thenReturn(Duration.ofDays(1));
        when(userService.spacesFor(user)).thenReturn(List.of());
        when(userService.view(user)).thenReturn(new UserView(7L, "alice", "Alice", "alice@example.com",
                "USER", 0, List.of(), 0, LocalDateTime.now()));
        when(userMapper.update(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db"));

        AuthResponse response = service.login(new LoginRequest("alice", "pw"), "1.2.3.4");

        assertThat(response.token()).isNotBlank();
    }

    private User activeUser() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setStatus(0);
        return user;
    }
}
