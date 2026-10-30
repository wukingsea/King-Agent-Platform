package com.king.ai.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.auth.dto.MfaEnableResponse;
import com.king.ai.auth.dto.MfaSetupResponse;
import com.king.ai.auth.dto.MfaStatusView;
import com.king.ai.common.CryptoService;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.entity.User;
import com.king.ai.system.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MfaServiceTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private MfaService service;
    private User user;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        KingAiProperties properties = new KingAiProperties();
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new MfaService(userMapper, passwordEncoder, new CryptoService(properties),
                new TotpService(), new ObjectMapper(), properties);
        user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setStatus(0);
        principal = new UserPrincipal(7L, "alice", "Alice", "USER", null, null, false);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
    }

    @Test
    void enrolmentIssuesRecoveryCodesAndSingleUseRecovery() {
        MfaSetupResponse setup = service.setup(principal, "pw");
        assertThat(setup.secret()).isNotBlank();
        assertThat(setup.otpauthUrl()).contains("secret=" + setup.secret());
        assertThat(service.status(principal).enabled()).isFalse();
        assertThat(service.status(principal).pending()).isTrue();

        long counter = System.currentTimeMillis() / 1000 / 30;
        MfaEnableResponse enabled = service.enable(principal,
                TotpService.generateCode(setup.secret(), counter));

        assertThat(enabled.recoveryCodes()).hasSize(8);
        MfaStatusView status = service.status(principal);
        assertThat(status.enabled()).isTrue();
        assertThat(status.recoveryCodesRemaining()).isEqualTo(8);

        String recoveryCode = enabled.recoveryCodes().get(0);
        assertThat(service.verifyForLogin(user, recoveryCode)).isTrue();
        assertThat(service.verifyForLogin(user, recoveryCode)).isFalse();
        assertThat(service.status(principal).recoveryCodesRemaining()).isEqualTo(7);
    }

    @Test
    void totpCodeIsAccepted() {
        MfaSetupResponse setup = service.setup(principal, "pw");
        long counter = System.currentTimeMillis() / 1000 / 30;
        service.enable(principal, TotpService.generateCode(setup.secret(), counter));

        assertThat(service.verifyForLogin(user,
                TotpService.generateCode(setup.secret(), counter))).isTrue();
    }

    @Test
    void disableRequiresPasswordAndCode() {
        MfaSetupResponse setup = service.setup(principal, "pw");
        long counter = System.currentTimeMillis() / 1000 / 30;
        service.enable(principal, TotpService.generateCode(setup.secret(), counter));

        service.disable(principal, "pw", TotpService.generateCode(setup.secret(), counter));

        assertThat(service.status(principal).enabled()).isFalse();
        assertThat(user.getMfaSecret()).isNull();
        assertThat(user.getMfaRecoveryCodes()).isNull();
    }
}
