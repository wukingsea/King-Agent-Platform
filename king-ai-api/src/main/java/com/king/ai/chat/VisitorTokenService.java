package com.king.ai.chat;

import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VisitorTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private final KingAiProperties properties;

    public String requireVisitor(HttpServletRequest request, HttpServletResponse response) {
        String visitorId = peekVisitor(request);
        if (visitorId != null) {
            return visitorId;
        }
        return issue(response);
    }

    public String peekVisitor(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String cookieName = properties.getPublicChat().getVisitorCookieName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String visitorId = verify(cookie.getValue());
                if (visitorId != null) {
                    return visitorId;
                }
            }
        }
        return null;
    }

    public String issue(HttpServletResponse response) {
        String visitorId = UUID.randomUUID().toString().replace("-", "");
        long expiresAt = Instant.now().plus(properties.getPublicChat().getVisitorTokenTtl()).getEpochSecond();
        String token = sign(visitorId, expiresAt);
        Duration maxAge = properties.getPublicChat().getVisitorTokenTtl();
        ResponseCookie cookie = ResponseCookie.from(properties.getPublicChat().getVisitorCookieName(), token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .secure(properties.getSecurity().isCookieSecure())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return visitorId;
    }

    private String sign(String visitorId, long expiresAt) {
        String payload = visitorId + "|" + expiresAt;
        byte[] mac = hmac(payload);
        return VERSION + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(mac);
    }

    private String verify(String token) {
        if (Texts.isBlank(token)) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return null;
        }
        String payload;
        byte[] signature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(payload), signature)) {
            return null;
        }
        String[] payloadParts = payload.split("\\|");
        if (payloadParts.length != 2) {
            return null;
        }
        try {
            long expiresAt = Long.parseLong(payloadParts[1]);
            if (expiresAt < Instant.now().getEpochSecond()) {
                return null;
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        String visitorId = payloadParts[0];
        if (!Texts.isValidVisitorId(visitorId)) {
            return null;
        }
        return visitorId;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发访客凭证", ex);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
