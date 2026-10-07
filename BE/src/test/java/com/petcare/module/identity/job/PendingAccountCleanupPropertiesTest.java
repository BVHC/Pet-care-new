package com.petcare.module.identity.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Bind {@code app.jobs.pending-account-cleanup} từ {@code application.yml} thật và fail-fast khi giá trị sai
 * (docs/adr/0013): cấu hình sai phải làm app dừng lúc khởi động, không được chạy với lô 0 hay không có time budget.
 */
class PendingAccountCleanupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PendingAccountCleanupProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PendingAccountCleanupProperties properties = context.getBean(PendingAccountCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("0 */15 * * * *");
            assertThat(properties.batchSize()).isEqualTo(200);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.slowWarn()).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("PENDING_ACCOUNT_CLEANUP_CRON=0 0 * * * *")
                .run(context -> assertThat(context.getBean(PendingAccountCleanupProperties.class).cron())
                        .isEqualTo("0 0 * * * *"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.pending-account-cleanup.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.pending-account-cleanup.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }

    @Test
    void zeroSlowWarnRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.pending-account-cleanup.slow-warn=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("slowWarn"));
    }

    @Test
    void blankCronRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.pending-account-cleanup.cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("cron"));
    }
}
