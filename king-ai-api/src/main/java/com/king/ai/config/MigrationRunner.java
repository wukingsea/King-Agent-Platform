package com.king.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;

/**
 * Runs Flyway in a short-lived pod and exits before the web server or bootstrap
 * runners can create business state. Flyway auto-configuration has already run
 * once during context refresh; the explicit migrate() call is an idempotent
 * verification and makes a simultaneous hook failure impossible to ignore.
 */
@Slf4j
@Component
@Profile("migrate")
@Order(Ordered.LOWEST_PRECEDENCE)
public class MigrationRunner implements ApplicationRunner {

    private final Flyway flyway;
    private final ConfigurableApplicationContext context;
    private final IntConsumer exiter;

    @Autowired
    public MigrationRunner(Flyway flyway, ConfigurableApplicationContext context) {
        // SpringApplication.exit closes the context but cannot guarantee the JVM dies if a
        // library left a non-daemon thread behind; System.exit removes that doubt.
        this(flyway, context, code -> System.exit(code));
    }

    MigrationRunner(Flyway flyway, ConfigurableApplicationContext context, IntConsumer exiter) {
        this.flyway = flyway;
        this.context = context;
        this.exiter = exiter;
    }

    @Override
    public void run(ApplicationArguments args) {
        var result = flyway.migrate();
        if (result != null) {
            log.info("Flyway migrate-only finished: {} migration(s) executed, schema {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
        int code = SpringApplication.exit(context, () -> 0);
        exiter.accept(code);
    }
}
