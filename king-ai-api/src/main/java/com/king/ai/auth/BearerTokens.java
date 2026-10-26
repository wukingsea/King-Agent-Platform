package com.king.ai.auth;

import com.king.ai.common.Texts;

public final class BearerTokens {

    private BearerTokens() {
    }

    public static String extract(String authorizationHeader) {
        if (Texts.isBlank(authorizationHeader)) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
