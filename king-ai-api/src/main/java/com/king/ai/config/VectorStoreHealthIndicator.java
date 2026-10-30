package com.king.ai.config;

import com.king.ai.knowledge.vector.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("vectorStore")
@RequiredArgsConstructor
public class VectorStoreHealthIndicator implements HealthIndicator {

    private final VectorStore vectorStore;

    @Override
    public Health health() {
        try {
            if (vectorStore.healthy()) {
                return Health.up().build();
            }
            return Health.down().withDetail("error", "vector store unavailable").build();
        } catch (RuntimeException ex) {
            return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
        }
    }
}
