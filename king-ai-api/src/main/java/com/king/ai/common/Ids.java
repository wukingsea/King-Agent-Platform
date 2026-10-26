package com.king.ai.common;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String publicId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String shortId(String prefix) {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase();
    }
}
