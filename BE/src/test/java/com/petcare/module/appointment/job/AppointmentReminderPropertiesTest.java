package com.petcare.module.appointment.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.CronExpression;

/** {@code app.jobs.appointment-reminder} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0051). */
class AppointmentReminderPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppointmentReminderProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AppointmentReminderProperties properties = context.getBean(AppointmentReminderProperties.class);
            assertThat(properties.cron()).isEqualTo("0 2/5 * * * *");
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    /**
     * Q11: trong một ngày, mọi lần chạy mặc định của ST03 rơi vào phút chia 5 dư 2 — không bao giờ trùng phút với ST05
     * (mỗi 5 phút từ phút 0) hay ST02 (mỗi 15 phút từ phút 0).
     */
    @Test
    void defaultCronNeverSharesAMinuteWithNoShowOrPendingCleanup() {
        CronExpression reminder = CronExpression.parse("0 2/5 * * * *");
        LocalDateTime t = LocalDateTime.of(2026, 10, 19, 0, 0).minusSeconds(1);
        for (int i = 0; i < 288; i++) {
            t = reminder.next(t);
            assertThat(t.getMinute() % 5).isEqualTo(2);
            assertThat(t.getSecond()).isZero();
        }
        assertThat(t).isEqualTo(LocalDateTime.of(2026, 10, 19, 23, 57));
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("APPOINTMENT_REMINDER_CRON=0 0 * * * *")
                .run(context -> assertThat(context.getBean(AppointmentReminderProperties.class).cron())
                        .isEqualTo("0 0 * * * *"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.appointment-reminder.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.appointment-reminder.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }
}
