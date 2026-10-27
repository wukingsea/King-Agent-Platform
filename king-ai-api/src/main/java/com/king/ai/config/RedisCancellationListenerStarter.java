package com.king.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!migrate")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisCancellationListenerStarter {

    private final RedisMessageListenerContainer chatCancellationListenerContainer;

    private volatile boolean started;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        tryStart();
    }

    @Scheduled(fixedDelayString = "${king-ai.chat-cancellation-retry-ms:30000}")
    public void retry() {
        if (!started) {
            tryStart();
        }
    }

    private synchronized void tryStart() {
        if (started) {
            return;
        }
        try {
            chatCancellationListenerContainer.start();
            started = true;
            log.info("Chat cancellation listener started");
        } catch (RuntimeException ex) {
            try {
                chatCancellationListenerContainer.stop();
            } catch (RuntimeException ignored) {
            }
            log.warn("Chat cancellation listener unavailable, will retry: {}", ex.getMessage());
        }
    }
}
