package com.king.ai.llm;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small per-model circuit breaker. It prevents hammering a failing upstream and lets the
 * chat runtime switch to the configured fallback model immediately.
 */
@Component
public class LlmCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        State state = states.get(key);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            if (state.openUntil == 0) {
                return true;
            }
            if (state.openUntil <= System.currentTimeMillis()) {
                state.failures = 0;
                state.openUntil = 0;
                return true;
            }
            return false;
        }
    }

    public void success(String key) {
        states.remove(key);
    }

    public void failure(String key) {
        states.compute(key, (ignored, state) -> {
            State value = state == null ? new State() : state;
            synchronized (value) {
                value.failures++;
                if (value.failures >= FAILURE_THRESHOLD) {
                    value.openUntil = System.currentTimeMillis() + OPEN_DURATION.toMillis();
                }
            }
            return value;
        });
    }

    private static final class State {
        private int failures;
        private long openUntil;
    }
}
