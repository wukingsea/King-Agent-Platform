package com.king.ai.agent.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic canary bucketing. A visitor always lands in the same bucket for a given
 * agent, so a rollout does not flip a visitor between versions on each request.
 */
public final class CanaryRouting {

    private CanaryRouting() {
    }

    public static int bucket(String visitorId, Long agentId) {
        String key = (visitorId == null ? "" : visitorId) + ":" + agentId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static boolean selected(String visitorId, Long agentId, Integer percent) {
        if (percent == null || percent <= 0 || visitorId == null || visitorId.isBlank()) {
            return false;
        }
        return bucket(visitorId, agentId) < Math.min(100, percent);
    }
}
