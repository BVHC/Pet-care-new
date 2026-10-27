package com.petcare.module.visit.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.CronExpression;

/** {@code app.jobs.follow-up-reminder} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0082). */
class FollowUpReminderPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FollowUpReminderProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FollowUpReminderProperties properties = context.getBean(FollowUpReminderProperties.class);
            assertThat(properties.cron()).isEqualTo("30 40 7-20 * * *");
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    /** Phút 40 giây 30 mỗi giờ từ 07:00 đến 20:00: lệch ST04 tái chủng (phút 10) và mọi job 5 phút (giây 0). */
    @Test
    void defaultCronRunsHourlyDuringTheDayAtSecond30() {
        CronExpression cron = CronExpression.parse("30 40 7-20 * * *");
        LocalDateTime t = LocalDateTime.of(2026, 10, 27, 0, 0).minusSeconds(1);
        for (int hour = 7; hour <= 20; hour++) {
            t = cron.next(t);
            assertThat(t).isEqualTo(LocalDateTime.of(2026, 10, 27, hour, 40, 30));
        }
        assertThat(cron.next(t)).isEqualTo(LocalDateTime.of(2026, 10, 28, 7, 40, 30));
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("FOLLOW_UP_REMINDER_CRON=0 0 * * * *")
                .run(context -> assertThat(context.getBean(FollowUpReminderProperties.class).cron())
                        .isEqualTo("0 0 * * * *"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.follow-up-reminder.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.follow-up-reminder.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }
}
