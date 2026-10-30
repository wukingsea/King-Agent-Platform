package com.king.ai.auth;

import com.king.ai.auth.dto.LoginSessionView;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryTokenStore implements TokenStore {

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(String token, Long userId, Duration ttl, String clientIp, String userAgent) {
        long now = System.currentTimeMillis();
        tokens.put(token, new Entry(userId, now, now + ttl.toMillis(), clientIp, userAgent));
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

    @Override
    public List<LoginSessionView> sessions(Long userId, String currentToken) {
        long now = System.currentTimeMillis();
        String currentId = currentToken == null ? null : TokenStore.sessionId(currentToken);
        List<LoginSessionView> result = new ArrayList<>();
        for (Map.Entry<String, Entry> item : tokens.entrySet()) {
            Entry entry = item.getValue();
            if (!entry.userId().equals(userId)) {
                continue;
            }
            if (entry.expiresAt() < now) {
                tokens.remove(item.getKey());
                continue;
            }
            String id = TokenStore.sessionId(item.getKey());
            result.add(new LoginSessionView(id, entry.clientIp(), entry.userAgent(),
                    toLocal(entry.createdAt()), toLocal(entry.expiresAt()), id.equals(currentId)));
        }
        result.sort(Comparator.comparing(LoginSessionView::createdAt).reversed());
        return result;
    }

    @Override
    public int deleteOthers(Long userId, String currentToken) {
        String currentId = currentToken == null ? null : TokenStore.sessionId(currentToken);
        int revoked = 0;
        for (Map.Entry<String, Entry> item : tokens.entrySet()) {
            if (!item.getValue().userId().equals(userId)) {
                continue;
            }
            if (TokenStore.sessionId(item.getKey()).equals(currentId)) {
                continue;
            }
            tokens.remove(item.getKey());
            revoked++;
        }
        return revoked;
    }

    private static LocalDateTime toLocal(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private record Entry(Long userId, long createdAt, long expiresAt, String clientIp, String userAgent) {
    }
}
