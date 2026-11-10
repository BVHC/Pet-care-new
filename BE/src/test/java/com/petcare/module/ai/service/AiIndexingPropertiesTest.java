package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** {@code app.ai.indexing} từ {@code application.yml} thật và fail-fast khi giá trị sai (ST22, docs/adr/0130). */
class AiIndexingPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiIndexingProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AiIndexingProperties properties = context.getBean(AiIndexingProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.batchSize()).isEqualTo(16);
            assertThat(properties.batchGap()).isEqualTo(Duration.ofMillis(800));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.retryBackoff()).isEqualTo(Duration.ofSeconds(20));
        });
    }

    @Test
    void canBeSwitchedOffByEnvironmentVariable() {
        runner.withPropertyValues("AI_INDEXING_ENABLED=false")
                .run(context -> assertThat(context.getBean(AiIndexingProperties.class).enabled()).isFalse());
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.ai.indexing.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroMaxAttemptsRejectedAtStartup() {
        runner.withPropertyValues("app.ai.indexing.max-attempts=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxAttempts"));
    }
}
