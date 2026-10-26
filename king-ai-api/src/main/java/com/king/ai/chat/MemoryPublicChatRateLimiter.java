package com.king.ai.chat;

import com.king.ai.common.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryPublicChatRateLimiter implements PublicChatRateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void check(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return;
        }
        long minute = System.currentTimeMillis() / 60000L;
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        synchronized (bucket) {
            if (bucket.minute != minute) {
                bucket.minute = minute;
                bucket.count = 0;
            }
            bucket.count++;
            if (bucket.count > limitPerMinute) {
                throw BizException.tooManyRequests("请求过于频繁，请稍后再试");
            }
        }
        if (buckets.size() > 20000) {
            cleanup(minute);
        }
    }

    private void cleanup(long currentMinute) {
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                if (bucket.minute < currentMinute - 2) {
                    iterator.remove();
                }
            }
        }
    }

    private static final class Bucket {
        private long minute = -1L;
        private int count;
    }
}
