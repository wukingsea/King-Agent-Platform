package com.king.ai.common.web;

import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final KingAiProperties properties;

    public String resolve(HttpServletRequest request) {
        if (properties.getSecurity().isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (!Texts.isBlank(forwarded)) {
                String first = forwarded.split(",")[0].trim();
                if (isValidIp(first)) {
                    return first;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (!Texts.isBlank(realIp) && isValidIp(realIp.trim())) {
                return realIp.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return Texts.isBlank(remote) ? "unknown" : remote;
    }

    private static boolean isValidIp(String value) {
        return value != null && !value.isBlank() && value.length() <= 64
                && value.matches("[0-9a-fA-F:.\\[\\]]+");
    }
}
