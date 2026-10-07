package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Bind {@code app.jobs.notification-outbox-cleanup} từ {@code application.yml} thật và fail-fast khi giá trị sai
 * (docs/adr/0016).
 */
class NotificationOutboxCleanupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationOutboxCleanupProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NotificationOutboxCleanupProperties properties = context.getBean(NotificationOutboxCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("0 40 3 * * *");
            assertThat(properties.sentRetentionDays()).isEqualTo(30);
            assertThat(properties.failedRetentionDays()).isEqualTo(90);
            assertThat(properties.batchSize()).isEqualTo(1000);
        });
    }

    @Test
    void zeroSentRetentionRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-outbox-cleanup.sent-retention-days=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("sentRetentionDays"));
    }

    @Test
    void zeroFailedRetentionRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-outbox-cleanup.failed-retention-days=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("failedRetentionDays"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-outbox-cleanup.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void blankCronRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-outbox-cleanup.cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("cron"));
    }
}
