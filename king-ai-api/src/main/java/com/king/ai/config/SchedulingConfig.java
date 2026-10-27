package com.king.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Owns @EnableScheduling so the migrate-only profile can opt out entirely:
 * a migration pod must never fire @Scheduled jobs while the context closes.
 */
@Configuration
@Profile("!migrate")
@EnableScheduling
public class SchedulingConfig {
}
