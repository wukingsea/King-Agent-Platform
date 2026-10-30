package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryLoginAttemptGuard implements LoginAttemptGuard {

    private final Map<String, Deque<Long>> pairFailures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> accountFailures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> ipFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> locks = new ConcurrentHashMap<>();

    @Override
    public void check(String username, String clientIp) {
        String user = normalize(username);
        String ip = normalize(clientIp);
        ensureUnlocked("pair:" + user + ":" + ip, LoginFailurePolicy::pairMessage);
        ensureUnlocked("account:" + user, LoginFailurePolicy::accountMessage);
        ensureUnlocked("ip:" + ip, LoginFailurePolicy::ipMessage);
    }

    @Override
    public void fail(String username, String clientIp) {
        String user = normalize(username);
        String ip = normalize(clientIp);
        long now = System.currentTimeMillis();
        String pairKey = "pair:" + user + ":" + ip;
        int pairCount = record(pairFailures, pairKey, now, LoginFailurePolicy.PAIR_WINDOW);
        int accountCount = record(accountFailures, "account:" + user, now,
                LoginFailurePolicy.ACCOUNT_WINDOW);
        int ipCount = record(ipFailures, "ip:" + ip, now, LoginFailurePolicy.IP_WINDOW);

        Duration pairLock = LoginFailurePolicy.lockDuration(pairCount);
        if (!pairLock.isZero()) {
            lock(pairKey, pairLock, now);
        }
        if (accountCount >= LoginFailurePolicy.ACCOUNT_THRESHOLD) {
            lock("account:" + user, LoginFailurePolicy.ACCOUNT_LOCK, now);
        }
        if (ipCount >= LoginFailurePolicy.IP_THRESHOLD) {
            lock("ip:" + ip, LoginFailurePolicy.IP_LOCK, now);
        }
    }

    @Override
    public void success(String username, String clientIp) {
        String pairKey = "pair:" + normalize(username) + ":" + normalize(clientIp);
        pairFailures.remove(pairKey);
        locks.remove(pairKey);
    }

    private void ensureUnlocked(String key, LongFunction<String> message) {
        Long expiresAt = locks.get(key);
        if (expiresAt == null) {
            return;
        }
        long remainingMillis = expiresAt - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            locks.remove(key, expiresAt);
            return;
        }
        throw BizException.tooManyRequests(message.apply((remainingMillis + 999) / 1000));
    }

    private int record(Map<String, Deque<Long>> windows, String key, long now, Duration window) {
        Deque<Long> failures = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (failures) {
            long threshold = now - window.toMillis();
            while (!failures.isEmpty() && failures.peekFirst() < threshold) {
                failures.removeFirst();
            }
            failures.addLast(now);
            return failures.size();
        }
    }

    private void lock(String key, Duration duration, long now) {
        long expiresAt = now + duration.toMillis();
        locks.merge(key, expiresAt, Math::max);
    }

    private String normalize(String value) {
        return Texts.isBlank(value) ? "unknown" : value.trim().toLowerCase();
    }
}
