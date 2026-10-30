package com.king.ai.auth;

import java.time.Duration;

/**
 * 登录失败保护策略：同一账号+IP 快速失败会渐进锁定，账号或 IP 维度的
 * 慢速分布式尝试也会被单独锁定，避免单点爆破或撞库绕过。
 */
public final class LoginFailurePolicy {

    public static final int PAIR_THRESHOLD = 5;
    public static final int ACCOUNT_THRESHOLD = 20;
    public static final int IP_THRESHOLD = 30;

    public static final Duration PAIR_WINDOW = Duration.ofHours(24);
    public static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);
    public static final Duration IP_WINDOW = Duration.ofMinutes(10);

    public static final Duration ACCOUNT_LOCK = Duration.ofHours(1);
    public static final Duration IP_LOCK = Duration.ofMinutes(15);

    private LoginFailurePolicy() {
    }

    public static Duration lockDuration(int pairFailures) {
        if (pairFailures >= 15) {
            return Duration.ofHours(2);
        }
        if (pairFailures >= 10) {
            return Duration.ofMinutes(30);
        }
        if (pairFailures >= PAIR_THRESHOLD) {
            return Duration.ofMinutes(5);
        }
        return Duration.ZERO;
    }

    public static String pairMessage(long remainingSeconds) {
        return "登录失败次数过多，请 " + minutes(remainingSeconds) + " 分钟后再试";
    }

    public static String accountMessage(long remainingSeconds) {
        return "该账号登录失败次数过多，请 " + minutes(remainingSeconds) + " 分钟后再试";
    }

    public static String ipMessage(long remainingSeconds) {
        return "当前 IP 登录失败次数过多，请 " + minutes(remainingSeconds) + " 分钟后再试";
    }

    private static long minutes(long remainingSeconds) {
        return Math.max(1, (remainingSeconds + 59) / 60);
    }
}
