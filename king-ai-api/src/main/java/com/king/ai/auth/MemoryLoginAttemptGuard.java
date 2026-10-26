package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryLoginAttemptGuard implements LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    @Override
    public void check(String username, String clientIp) {
        Deque<Long> window = failures.get(key(username, clientIp));
        if (window == null) {
            return;
        }
        synchronized (window) {
            trim(window);
            if (window.size() >= MAX_FAILURES) {
                throw BizException.tooManyRequests("登录失败次数过多，请 5 分钟后再试");
            }
        }
    }

    @Override
    public void fail(String username, String clientIp) {
        Deque<Long> window = failures.computeIfAbsent(key(username, clientIp), key -> new ArrayDeque<>());
        synchronized (window) {
            trim(window);
            window.addLast(System.currentTimeMillis());
        }
    }

    @Override
    public void success(String username, String clientIp) {
        failures.remove(key(username, clientIp));
    }

    private void trim(Deque<Long> window) {
        long threshold = System.currentTimeMillis() - WINDOW_MILLIS;
        while (!window.isEmpty() && window.peekFirst() < threshold) {
            window.removeFirst();
        }
    }

    private String key(String username, String clientIp) {
        return normalize(username) + ":" + normalize(clientIp);
    }

    private String normalize(String value) {
        return Texts.isBlank(value) ? "unknown" : value.trim().toLowerCase();
    }
}
