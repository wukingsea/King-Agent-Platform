package com.king.ai.sso;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * HMAC-signed OIDC state: carries the nonce and an expiry, so a callback that this
 * platform did not initiate cannot complete a login.
 */
@Service
@RequiredArgsConstructor
public class OidcStateSigner {

    private final KingAiProperties properties;

    public String issue(String nonce, Duration ttl) {
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        String payload = nonce + ":" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public String verify(String state) {
        if (Texts.isBlank(state) || !state.contains(".")) {
            throw BizException.unauthorized("SSO state 无效，请重新登录");
        }
        int separator = state.lastIndexOf('.');
        String encoded = state.substring(0, separator);
        String signature = state.substring(separator + 1);
        if (!MessageDigest.isEqual(sign(encoded).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.unauthorized("SSO state 无效，请重新登录");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw BizException.unauthorized("SSO state 无效，请重新登录");
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            throw BizException.unauthorized("SSO state 无效，请重新登录");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(payload.substring(colon + 1));
        } catch (NumberFormatException ex) {
            throw BizException.unauthorized("SSO state 无效，请重新登录");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw BizException.unauthorized("SSO 登录已超时，请重新登录");
        }
        return payload.substring(0, colon);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发 SSO state", ex);
        }
    }
}
