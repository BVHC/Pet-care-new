package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Bind {@code app.jobs.notification-cleanup} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0112). */
class NotificationCleanupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationCleanupProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NotificationCleanupProperties properties = context.getBean(NotificationCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("23 50 3 * * *");
            assertThat(properties.readRetentionDays()).isEqualTo(30);
            assertThat(properties.unreadRetentionDays()).isEqualTo(90);
            assertThat(properties.batchSize()).isEqualTo(1000);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void zeroReadRetentionRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-cleanup.read-retention-days=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("readRetentionDays"));
    }

    @Test
    void zeroUnreadRetentionRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-cleanup.unread-retention-days=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("unreadRetentionDays"));
    }

    /** Dòng chưa đọc không được bị xóa sớm hơn hoặc cùng lúc với dòng đã đọc. */
    @Test
    void unreadNotLongerThanReadRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-cleanup.unread-retention-days=30")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("unreadRetentionDays phải lớn hơn readRetentionDays"));
        runner.withPropertyValues("app.jobs.notification-cleanup.unread-retention-days=10")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("unreadRetentionDays phải lớn hơn readRetentionDays"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-cleanup.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-cleanup.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }

    @Test
    void blankCronRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.notification-cleanup.cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("cron"));
    }
}
