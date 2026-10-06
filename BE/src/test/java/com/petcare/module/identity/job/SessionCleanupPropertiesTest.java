package com.petcare.module.identity.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Bind {@code app.jobs.session-cleanup} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0008):
 * cấu hình sai phải làm app dừng lúc khởi động, không được chạy với lô 0 hay thời gian lưu 0.
 */
class SessionCleanupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SessionCleanupProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SessionCleanupProperties properties = context.getBean(SessionCleanupProperties.class);
            assertThat(properties.cron()).isEqualTo("0 0 3 * * *");
            assertThat(properties.retentionDays()).isEqualTo(30);
            assertThat(properties.batchSize()).isEqualTo(1000);
        });
    }

    @Test
    void zeroRetentionRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.session-cleanup.retention-days=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("retentionDays"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.session-cleanup.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void blankCronRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.session-cleanup.cron=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("cron"));
    }
}
