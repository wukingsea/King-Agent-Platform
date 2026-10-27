package com.king.ai.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * The migrate-only runner must invoke Flyway exactly once, then close the
 * context via {@link SpringApplication#exit} and hand the resulting exit code
 * to an injectable exiter so tests never touch System.exit. On a Flyway
 * failure the exception must propagate before any exit decision is made.
 */
@ExtendWith(MockitoExtension.class)
class MigrationRunnerTest {

    @Mock
    private Flyway flyway;
    @Mock
    private ConfigurableApplicationContext context;

    @Test
    void runsFlywayOnceThenExitsWithCodeFromExitHook() {
        List<Integer> exitCodes = new ArrayList<>();
        try (MockedStatic<SpringApplication> application = mockStatic(SpringApplication.class)) {
            application.when(() -> SpringApplication.exit(eq(context), any(ExitCodeGenerator[].class)))
                    .thenReturn(7);

            new MigrationRunner(flyway, context, exitCodes::add)
                    .run(new DefaultApplicationArguments(new String[]{}));

            verify(flyway).migrate();
            application.verify(() -> SpringApplication.exit(eq(context), any(ExitCodeGenerator[].class)));
        }
        assertThat(exitCodes).containsExactly(7);
    }

    @Test
    void flywayFailurePropagatesBeforeAnyExit() {
        List<Integer> exitCodes = new ArrayList<>();
        doThrow(new IllegalStateException("flyway migrate failed")).when(flyway).migrate();

        try (MockedStatic<SpringApplication> application = mockStatic(SpringApplication.class)) {
            assertThatThrownBy(() -> new MigrationRunner(flyway, context, exitCodes::add)
                    .run(new DefaultApplicationArguments(new String[]{})))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("flyway migrate failed");

            application.verifyNoInteractions();
        }
        assertThat(exitCodes).isEmpty();
    }
}
