package com.king.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TracingConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void localConfigSamplesEveryTraceButDoesNotExportByDefault() throws IOException {
        PropertySource<?> source = load("application", "application.yml");

        assertThat(source.getProperty("management.tracing.enabled")).isEqualTo(true);
        assertThat(source.getProperty("management.tracing.sampling.probability")).isEqualTo(1.0);
        assertThat(source.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(source.getProperty("management.otlp.tracing.export.enabled")).isEqualTo(false);
    }

    @Test
    void productionConfigDefaultsToTenPercentAndEnvOverrides() throws IOException {
        PropertySource<?> source = load("application-prod", "application-prod.yml");

        assertThat(String.valueOf(source.getProperty("management.tracing.sampling.probability")))
                .isEqualTo("${KING_AI_TRACE_SAMPLE:0.1}");
        assertThat(String.valueOf(source.getProperty("management.otlp.tracing.export.enabled")))
                .isEqualTo("${OTEL_TRACING_EXPORT_ENABLED:false}");
    }

    @Test
    void chatHistogramsAreEnabledForTtftAndDuration() throws IOException {
        PropertySource<?> source = load("application", "application.yml");

        assertThat(source.getProperty(
                "management.metrics.distribution.percentiles-histogram.[king.ai.chat.ttft]"))
                .isEqualTo(true);
        assertThat(source.getProperty(
                "management.metrics.distribution.percentiles-histogram.[king.ai.chat.duration]"))
                .isEqualTo(true);
    }

    private PropertySource<?> load(String name, String path) throws IOException {
        return loader.load(name, new ClassPathResource(path)).get(0);
    }
}
