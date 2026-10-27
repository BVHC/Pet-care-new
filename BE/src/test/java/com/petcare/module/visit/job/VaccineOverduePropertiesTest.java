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

/** {@code app.jobs.vaccine-overdue} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0083). */
class VaccineOverduePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VaccineOverdueProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            VaccineOverdueProperties properties = context.getBean(VaccineOverdueProperties.class);
            assertThat(properties.cron()).isEqualTo("30 25 7-20 * * *");
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    /** Mỗi giờ từ 07:00 đến 20:00 ở phút 25 giây 30: 14 lần một ngày, lệch ST04 (phút 10 / 40) và mọi job 5 phút. */
    @Test
    void defaultCronRunsHourlyDuringTheDayAtMinute25Second30() {
        CronExpression cron = CronExpression.parse("30 25 7-20 * * *");
        LocalDateTime t = LocalDateTime.of(2026, 10, 27, 0, 0).minusSeconds(1);
        for (int hour = 7; hour <= 20; hour++) {
            t = cron.next(t);
            assertThat(t).isEqualTo(LocalDateTime.of(2026, 10, 27, hour, 25, 30));
        }
        assertThat(cron.next(t)).isEqualTo(LocalDateTime.of(2026, 10, 28, 7, 25, 30));
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("VACCINE_OVERDUE_CRON=0 0 * * * *")
                .run(context -> assertThat(context.getBean(VaccineOverdueProperties.class).cron())
                        .isEqualTo("0 0 * * * *"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.vaccine-overdue.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.vaccine-overdue.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }
}
