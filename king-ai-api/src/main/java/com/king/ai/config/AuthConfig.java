package com.king.ai.config;

import com.king.ai.auth.MemoryTokenStore;
import com.king.ai.auth.RedisTokenStore;
import com.king.ai.auth.TokenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    @ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
    public TokenStore redisTokenStore(StringRedisTemplate redis, KingAiProperties properties) {
        return new RedisTokenStore(redis, properties.getSessionTtl());
    }

    @Bean
    @ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
    public TokenStore memoryTokenStore() {
        return new MemoryTokenStore();
    }
}
