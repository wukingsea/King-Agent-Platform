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
                String[] hops = forwarded.split(",");
                int trustedHops = Math.max(1, properties.getSecurity().getTrustedProxyHops());
                if (hops.length >= trustedHops) {
                    String candidate = hops[hops.length - trustedHops].trim();
                    if (isValidIp(candidate)) {
                        return candidate;
                    }
                }
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
