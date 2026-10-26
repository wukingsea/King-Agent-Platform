package com.king.ai.config;

import com.king.ai.chat.RedisChatCancellationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisStateConfig {

    @Bean
    public RedisMessageListenerContainer chatCancellationListenerContainer(
            RedisConnectionFactory connectionFactory, RedisChatCancellationRegistry registry) {
        RedisMessageListenerContainer container = new ManualStartRedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                        registry.cancelLocal(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(RedisChatCancellationRegistry.CANCEL_CHANNEL));
        return container;
    }

    private static final class ManualStartRedisMessageListenerContainer extends RedisMessageListenerContainer {

        @Override
        public boolean isAutoStartup() {
            return false;
        }
    }
}
