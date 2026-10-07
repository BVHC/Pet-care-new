package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Bind {@code app.jobs.notification-outbox} từ {@code application.yml} thật và dừng app khi giá trị sai. */
class NotificationOutboxPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationOutboxProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NotificationOutboxProperties p = context.getBean(NotificationOutboxProperties.class);
            assertThat(p.cron()).isEqualTo("*/10 * * * * *");
            assertThat(p.priorityCron()).isEqualTo("*/5 * * * * *");
            assertThat(p.timeBudget()).isEqualTo(Duration.ofSeconds(8));
            assertThat(p.priorityTimeBudget()).isEqualTo(Duration.ofSeconds(4));
            assertThat(p.maxAttempts()).isEqualTo(5);
            assertThat(p.initialBackoff()).isEqualTo(Duration.ofMinutes(1));
            assertThat(p.mailFrom()).isEqualTo("no-reply@petcare.local");
            assertThat(p.lagWarn()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.lagWarnPriority()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.inAppCron()).isEqualTo("*/5 * * * * *");
            assertThat(p.inAppTimeBudget()).isEqualTo(Duration.ofSeconds(4));
            assertThat(p.lagWarnInApp()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.normalBatchSize()).isEqualTo(20);
            assertThat(p.normalBatchSendBudget()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void zeroMaxAttemptsRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.max-attempts=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxAttempts"));
    }

    @Test
    void zeroTimeBudgetRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.priority-time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("priorityTimeBudget"));
    }

    @Test
    void invalidMailFromRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.mail-from=not an address")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("mailFrom"));
    }

    @Test
    void blankInAppCronRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.in-app-cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("inAppCron"));
    }

    @Test
    void zeroInAppTimeBudgetRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.in-app-time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("inAppTimeBudget"));
    }

    @Test
    void zeroBatchSizeRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.normal-batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("normalBatchSize"));
    }

    @Test
    void oversizedBatchRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.normal-batch-size=101")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("normalBatchSize"));
    }

    /** Ngân sách lô > 15 s: transaction giữ khóa có thể vượt idle_in_transaction_session_timeout 60 s (ADR-0017). */
    @Test
    void batchSendBudgetAboveFifteenSecondsRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.normal-batch-send-budget=16s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("normalBatchSendBudget"));
    }

    @Test
    void blankCronRejected() {
        runner.withPropertyValues("app.jobs.notification-outbox.priority-cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("priorityCron"));
    }
}
