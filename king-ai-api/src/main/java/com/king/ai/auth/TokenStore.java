package com.king.ai.auth;

import java.time.Duration;

public interface TokenStore {

    void save(String token, Long userId, Duration ttl);

    Long findUserId(String token);

    void delete(String token);

    void deleteByUserId(Long userId);
}
