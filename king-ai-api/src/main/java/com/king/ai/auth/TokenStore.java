package com.king.ai.auth;

import com.king.ai.auth.dto.LoginSessionView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

public interface TokenStore {

    void save(String token, Long userId, Duration ttl, String clientIp, String userAgent);

    Long findUserId(String token);

    void delete(String token);

    void deleteByUserId(Long userId);

    /**
     * Lists active sessions for the user without exposing tokens. {@code currentToken} marks
     * the caller so the UI can label the device it is running on.
     */
    List<LoginSessionView> sessions(Long userId, String currentToken);

    /**
     * Revokes every session except the caller's, so one compromised device can be dropped
     * without forcing a re-login on the device the user is currently using.
     */
    int deleteOthers(Long userId, String currentToken);

    /**
     * Stable, non-reversible public identifier for a session token.
     */
    static String sessionId(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
