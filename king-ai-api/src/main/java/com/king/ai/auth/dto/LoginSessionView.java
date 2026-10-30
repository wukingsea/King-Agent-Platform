package com.king.ai.auth.dto;

import java.time.LocalDateTime;

/**
 * One active login session. The id is a truncated hash of the token, so operators can
 * recognise devices without the API ever exposing session credentials.
 */
public record LoginSessionView(
        String id,
        String clientIp,
        String userAgent,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean current
) {
}
