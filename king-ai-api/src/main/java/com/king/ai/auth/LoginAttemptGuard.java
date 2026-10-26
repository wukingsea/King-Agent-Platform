package com.king.ai.auth;

public interface LoginAttemptGuard {

    void check(String username, String clientIp);

    void fail(String username, String clientIp);

    void success(String username, String clientIp);
}
