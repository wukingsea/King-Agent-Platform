package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Stateless MFA challenge: an HMAC-signed user id plus expiry. Any replica can verify it,
 * and it can never be replayed after the short TTL.
 */
@Service
@RequiredArgsConstructor
public class MfaChallengeService {

    private final KingAiProperties properties;

    public String issue(Long userId) {
        long expiresAt = Instant.now().plus(properties.getMfa().getChallengeTtl()).getEpochSecond();
        String payload = userId + ":" + expiresAt;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    }

    public Long verify(String challenge) {
        if (Texts.isBlank(challenge) || !challenge.contains(".")) {
            throw BizException.unauthorized("二次验证会话无效，请重新登录");
        }
        int separator = challenge.lastIndexOf('.');
        String encoded = challenge.substring(0, separator);
        String signature = challenge.substring(separator + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw BizException.unauthorized("二次验证会话无效，请重新登录");
        }
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.unauthorized("二次验证会话无效，请重新登录");
        }
        int colon = payload.indexOf(':');
        if (colon <= 0) {
            throw BizException.unauthorized("二次验证会话无效，请重新登录");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(payload.substring(colon + 1));
        } catch (NumberFormatException ex) {
            throw BizException.unauthorized("二次验证会话无效，请重新登录");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw BizException.unauthorized("二次验证已过期，请重新登录");
        }
        try {
            return Long.parseLong(payload.substring(0, colon));
        } catch (NumberFormatException ex) {
            throw BizException.unauthorized("二次验证会话无效，请重新登录");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发二次验证会话", ex);
        }
    }
}
