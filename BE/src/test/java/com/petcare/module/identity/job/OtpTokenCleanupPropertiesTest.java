package com.petcare.module.identity.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Bind {@code app.jobs.otp-token-cleanup} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0111). */
class OtpTokenCleanupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OtpTokenCleanupProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OtpTokenCleanupProperties properties = context.getBean(OtpTokenCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("17 20 3 * * *");
            assertThat(properties.retentionDays()).isEqualTo(1);
            assertThat(properties.batchSize()).isEqualTo(1000);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void zeroRetentionRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.otp-token-cleanup.retention-days=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("retentionDays"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.otp-token-cleanup.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.otp-token-cleanup.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }

    @Test
    void blankCronRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.otp-token-cleanup.cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("cron"));
    }
}
