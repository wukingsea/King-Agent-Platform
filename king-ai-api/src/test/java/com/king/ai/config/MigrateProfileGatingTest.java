package com.king.ai.config;

import com.king.ai.KingAiApplication;
import com.king.ai.agent.AgentPublicationBackfill;
import com.king.ai.compliance.export.ExportJobWorker;
import com.king.ai.knowledge.KnowledgeIndexWorker;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the migrate-only Kubernetes Job profile. A migration pod
 * must not inherit @EnableScheduling from the main class and must not register
 * any startup side-effect bean: runners and ApplicationReadyEvent listeners
 * fire while the migration context is coming up and would mutate business
 * state before the pod exits.
 */
class MigrateProfileGatingTest {

    private static final List<Class<?>> GATED_STARTUP_BEANS = List.of(
            AdminBootstrap.class,
            LegacySecretMigrator.class,
            DemoBootstrap.class,
            AgentPublicationBackfill.class,
            KnowledgeIndexWorker.class,
            ExportJobWorker.class,
            RedisCancellationListenerStarter.class
    );

    @Test
    void schedulingAnnotationLivesOnlyOnMigrateAwareConfig() {
        assertThat(KingAiApplication.class.getAnnotation(EnableScheduling.class))
                .as("KingAiApplication must not carry @EnableScheduling; migrate pods would inherit it")
                .isNull();
        assertThat(SchedulingConfig.class.getAnnotation(EnableScheduling.class))
                .as("SchedulingConfig must carry @EnableScheduling for normal pods")
                .isNotNull();
    }

    @Test
    void schedulingConfigIsDisabledUnderMigrateProfile() {
        Profile profile = SchedulingConfig.class.getAnnotation(Profile.class);
        assertThat(profile)
                .as("SchedulingConfig must carry @Profile(\"!migrate\")")
                .isNotNull();
        assertThat(profile.value()).containsExactly("!migrate");
    }

    @Test
    void everyStartupSideEffectBeanIsGatedFromMigrateProfile() {
        for (Class<?> beanType : GATED_STARTUP_BEANS) {
            Profile profile = beanType.getAnnotation(Profile.class);
            assertThat(profile)
                    .as(beanType.getSimpleName() + " must carry @Profile(\"!migrate\")")
                    .isNotNull();
            assertThat(profile.value())
                    .as(beanType.getSimpleName() + " must exclude the migrate profile")
                    .containsExactly("!migrate");
        }
    }
}
