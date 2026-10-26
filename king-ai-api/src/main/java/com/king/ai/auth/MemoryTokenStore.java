package com.king.ai.auth;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryTokenStore implements TokenStore {

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(String token, Long userId, Duration ttl) {
        tokens.put(token, new Entry(userId, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public Long findUserId(String token) {
        Entry entry = tokens.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            tokens.remove(token);
            return null;
        }
        return entry.userId();
    }

    @Override
    public void delete(String token) {
        tokens.remove(token);
    }

    @Override
    public void deleteByUserId(Long userId) {
        tokens.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
    }

    private record Entry(Long userId, long expiresAt) {
    }
}
