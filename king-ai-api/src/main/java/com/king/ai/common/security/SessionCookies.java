package com.king.ai.common.security;

import com.king.ai.config.KingAiProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class SessionCookies {

    private final KingAiProperties properties;

    public String find(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = properties.getSecurity().getSessionCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void write(HttpServletResponse response, String token) {
        Duration ttl = properties.getSessionTtl();
        ResponseCookie cookie = ResponseCookie.from(properties.getSecurity().getSessionCookieName(), token)
                .httpOnly(true)
                .secure(properties.getSecurity().isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(properties.getSecurity().getSessionCookieName(), "")
                .httpOnly(true)
                .secure(properties.getSecurity().isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
