package com.king.ai.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * Validates an OIDC ID Token: RS256 signature over header.payload, issuer, audience,
 * expiry/not-before with a small clock skew, and the nonce that binds the token to the
 * authorization request this platform started.
 */
@Component
@RequiredArgsConstructor
public class OidcTokenValidator {

    private static final int MAX_SEGMENT_BYTES = 1_000_000;
    private static final long CLOCK_SKEW_SECONDS = 60;

    public interface KeyResolver {
        PublicKey resolve(String keyId);
    }

    private final ObjectMapper objectMapper;

    public JsonNode validate(String idToken, String expectedNonce, String issuer, String clientId,
                             KeyResolver keys) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw BizException.unauthorized("ID Token 格式不正确");
        }
        JsonNode header = decode(parts[0]);
        JsonNode claims = decode(parts[1]);
        if (!"RS256".equals(text(header, "alg"))) {
            throw BizException.unauthorized("ID Token 签名算法不受支持");
        }
        PublicKey key = keys.resolve(text(header, "kid"));
        if (key == null) {
            throw BizException.unauthorized("ID Token 签名密钥不存在");
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw BizException.unauthorized("ID Token 签名校验失败");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.unauthorized("ID Token 签名校验失败");
        }

        if (!normalize(issuer).equals(normalize(text(claims, "iss")))) {
            throw BizException.unauthorized("ID Token 签发方不匹配");
        }
        if (!audienceMatches(claims, clientId)) {
            throw BizException.unauthorized("ID Token 受众不匹配");
        }
        long now = Instant.now().getEpochSecond();
        long expiresAt = claims.path("exp").asLong(0);
        if (expiresAt <= 0 || now - CLOCK_SKEW_SECONDS > expiresAt) {
            throw BizException.unauthorized("ID Token 已过期");
        }
        if (claims.has("nbf") && now + CLOCK_SKEW_SECONDS < claims.path("nbf").asLong(0)) {
            throw BizException.unauthorized("ID Token 尚未生效");
        }
        String nonce = text(claims, "nonce");
        if (nonce == null || expectedNonce == null || !MessageDigest.isEqual(
                nonce.getBytes(StandardCharsets.UTF_8), expectedNonce.getBytes(StandardCharsets.UTF_8))) {
            throw BizException.unauthorized("ID Token 随机数不匹配");
        }
        return claims;
    }

    private boolean audienceMatches(JsonNode claims, String clientId) {
        JsonNode audience = claims.get("aud");
        if (audience == null) {
            return false;
        }
        if (audience.isArray()) {
            for (JsonNode item : audience) {
                if (clientId.equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }
        return clientId.equals(audience.asText());
    }

    private JsonNode decode(String segment) {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(segment);
            if (payload.length > MAX_SEGMENT_BYTES) {
                throw BizException.unauthorized("ID Token 内容过大");
            }
            return objectMapper.readTree(payload);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.unauthorized("ID Token 解析失败");
        }
    }

    private static String normalize(String issuer) {
        if (issuer == null) {
            return "";
        }
        String value = issuer.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return Texts.isBlank(text) ? null : text.trim();
    }
}
