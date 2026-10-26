package com.king.ai.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCircuitBreakerTest {

    @Test
    void allowsRequestsBeforeAnyFailure() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        assertThat(breaker.allow("openai:gpt-4o")).isTrue();
    }

    @Test
    void opensAfterThreeConsecutiveFailures() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        breaker.failure("openai:gpt-4o");
        breaker.failure("openai:gpt-4o");
        assertThat(breaker.allow("openai:gpt-4o")).isTrue();

        breaker.failure("openai:gpt-4o");
        assertThat(breaker.allow("openai:gpt-4o")).isFalse();
    }

    @Test
    void successResetsFailureState() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        breaker.failure("openai:gpt-4o");
        breaker.failure("openai:gpt-4o");
        breaker.success("openai:gpt-4o");
        breaker.failure("openai:gpt-4o");
        breaker.failure("openai:gpt-4o");

        assertThat(breaker.allow("openai:gpt-4o")).isTrue();
    }

    @Test
    void failuresAreIsolatedPerModel() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        breaker.failure("openai:gpt-4o");
        breaker.failure("openai:gpt-4o");
        breaker.failure("openai:gpt-4o");

        assertThat(breaker.allow("openai:gpt-4o")).isFalse();
        assertThat(breaker.allow("anthropic:claude")).isTrue();
    }
}
