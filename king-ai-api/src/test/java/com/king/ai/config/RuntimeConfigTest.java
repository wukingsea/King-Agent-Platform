package com.king.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigTest {

    @Test
    void sharedHttpClientNeverFollowsRedirects() {
        RuntimeConfig config = new RuntimeConfig();

        assertThat(config.httpClient().followRedirects()).isFalse();
        assertThat(config.httpClient().followSslRedirects()).isFalse();
    }
}
