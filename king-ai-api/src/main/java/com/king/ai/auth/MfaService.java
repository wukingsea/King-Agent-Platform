package com.king.ai.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.auth.dto.MfaEnableResponse;
import com.king.ai.auth.dto.MfaSetupResponse;
import com.king.ai.auth.dto.MfaStatusView;
import com.king.ai.common.BizException;
import com.king.ai.common.CryptoService;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.entity.User;
import com.king.ai.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * TOTP enrolment and verification. The shared secret and the recovery code hashes are
 * AES-GCM encrypted at rest; recovery codes are stored only as SHA-256 hashes and are
 * single use.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_CODE_LENGTH = 10;
    private static final int TOTP_WINDOW = 1;
    private static final TypeReference<List<String>> HASH_LIST = new TypeReference<>() {
    };

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;
    private final TotpService totpService;
    private final ObjectMapper objectMapper;
    private final KingAiProperties properties;
    private final SecureRandom random = new SecureRandom();

    public MfaStatusView status(UserPrincipal principal) {
        User user = require(principal.id());
        boolean pending = !isEnabled(user) && !Texts.isBlank(user.getMfaSecret());
        return new MfaStatusView(isEnabled(user), pending, user.getMfaEnrolledAt(),
                pending || isEnabled(user) ? recoveryHashes(user).size() : 0);
    }

    public MfaSetupResponse setup(UserPrincipal principal, String password) {
        requireGloballyEnabled();
        User user = require(principal.id());
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw BizException.badRequest("当前密码不正确");
        }
        if (isEnabled(user)) {
            throw BizException.conflict("二次验证已启用，请先关闭后再重新绑定");
        }
        String secret = totpService.newSecret();
        user.setMfaSecret(cryptoService.encrypt(secret));
        user.setMfaEnabled(0);
        user.setMfaEnrolledAt(null);
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        return new MfaSetupResponse(secret, totpService.otpauthUrl(properties.getMfa().getIssuer(),
                user.getUsername(), secret));
    }

    public MfaEnableResponse enable(UserPrincipal principal, String code) {
        requireGloballyEnabled();
        User user = require(principal.id());
        if (isEnabled(user)) {
            throw BizException.conflict("二次验证已启用");
        }
        if (Texts.isBlank(user.getMfaSecret())) {
            throw BizException.badRequest("请先生成二次验证密钥");
        }
        if (!totpService.verify(cryptoService.decrypt(user.getMfaSecret()), code, TOTP_WINDOW)) {
            throw BizException.badRequest("验证码不正确");
        }
        List<String> plainCodes = generateRecoveryCodes(properties.getMfa().getRecoveryCodeCount());
        user.setMfaEnabled(1);
        user.setMfaEnrolledAt(LocalDateTime.now());
        user.setMfaRecoveryCodes(cryptoService.encrypt(encodeHashes(
                plainCodes.stream().map(value -> hash(normalizeRecoveryCode(value))).toList())));
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        return new MfaEnableResponse(plainCodes);
    }

    public void disable(UserPrincipal principal, String password, String code) {
        User user = require(principal.id());
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw BizException.badRequest("当前密码不正确");
        }
        if (!isEnabled(user)) {
            throw BizException.badRequest("二次验证未启用");
        }
        if (!matchesTotp(user, code) && !consumeRecoveryCode(user, code)) {
            throw BizException.badRequest("验证码不正确");
        }
        user.setMfaEnabled(0);
        user.setMfaSecret(null);
        user.setMfaRecoveryCodes(null);
        user.setMfaEnrolledAt(null);
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
    }

    /**
     * Verifies a TOTP code or a recovery code during login. A successful recovery code is
     * consumed so it can never be replayed.
     */
    public boolean verifyForLogin(User user, String code) {
        return matchesTotp(user, code) || consumeRecoveryCode(user, code);
    }

    private boolean matchesTotp(User user, String code) {
        if (Texts.isBlank(user.getMfaSecret())) {
            return false;
        }
        return totpService.verify(cryptoService.decrypt(user.getMfaSecret()), code, TOTP_WINDOW);
    }

    private boolean consumeRecoveryCode(User user, String code) {
        if (Texts.isBlank(code) || Texts.isBlank(user.getMfaRecoveryCodes())) {
            return false;
        }
        String hash = hash(normalizeRecoveryCode(code));
        List<String> hashes = new ArrayList<>(recoveryHashes(user));
        if (!hashes.remove(hash)) {
            return false;
        }
        user.setMfaRecoveryCodes(cryptoService.encrypt(encodeHashes(hashes)));
        OptimisticLocks.requireUpdated(userMapper.updateById(user), "用户");
        log.info("MFA recovery code used for user {}", user.getId());
        return true;
    }

    private List<String> recoveryHashes(User user) {
        if (Texts.isBlank(user.getMfaRecoveryCodes())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(cryptoService.decrypt(user.getMfaRecoveryCodes()), HASH_LIST);
        } catch (Exception ex) {
            log.warn("Failed to read MFA recovery codes for user {}: {}", user.getId(),
                    ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private String encodeHashes(List<String> hashes) {
        try {
            return objectMapper.writeValueAsString(hashes);
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存恢复码", ex);
        }
    }

    private List<String> generateRecoveryCodes(int count) {
        List<String> codes = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            StringBuilder builder = new StringBuilder(RECOVERY_CODE_LENGTH + 1);
            for (int j = 0; j < RECOVERY_CODE_LENGTH; j++) {
                if (j == RECOVERY_CODE_LENGTH / 2) {
                    builder.append('-');
                }
                builder.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
            }
            codes.add(builder.toString());
        }
        return codes;
    }

    private static String normalizeRecoveryCode(String code) {
        return code.replace("-", "").replace(" ", "").trim().toUpperCase();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法校验恢复码", ex);
        }
    }

    private boolean isEnabled(User user) {
        return user.getMfaEnabled() != null && user.getMfaEnabled() == 1;
    }

    private void requireGloballyEnabled() {
        if (!properties.getMfa().isEnabled()) {
            throw BizException.conflict("管理员已关闭二次验证功能");
        }
    }

    private User require(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }
}
