package com.petcare.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.CronExpression;

/** {@code app.jobs.chat-log-cleanup} từ {@code application.yml} thật và fail-fast khi giá trị sai (ST23, docs/adr/0130). */
class ChatLogCleanupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatLogCleanupProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ChatLogCleanupProperties properties = context.getBean(ChatLogCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("29 0 4 * * *");
            assertThat(properties.batchSize()).isEqualTo(1000);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void defaultCronRunsOncePerDayAt040029() {
        CronExpression cron = CronExpression.parse("29 0 4 * * *");
        LocalDateTime next = cron.next(LocalDateTime.of(2026, 11, 10, 0, 0));

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 11, 10, 4, 0, 29));
        assertThat(cron.next(next)).isEqualTo(LocalDateTime.of(2026, 11, 11, 4, 0, 29));
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("CHAT_LOG_CLEANUP_CRON=-")
                .run(context -> assertThat(context.getBean(ChatLogCleanupProperties.class).cron()).isEqualTo("-"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.chat-log-cleanup.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.chat-log-cleanup.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }
}
