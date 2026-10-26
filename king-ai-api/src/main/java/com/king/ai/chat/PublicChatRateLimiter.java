package com.king.ai.chat;

public interface PublicChatRateLimiter {

    void check(String key, int limitPerMinute);
}
