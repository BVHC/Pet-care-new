package com.petcare.module.appointment.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** {@code app.jobs.no-show} từ {@code application.yml} thật và fail-fast khi giá trị sai (docs/adr/0048). */
class NoShowPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NoShowProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NoShowProperties properties = context.getBean(NoShowProperties.class);
            assertThat(properties.cron()).isEqualTo("0 */5 * * * *");
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("NO_SHOW_CRON=0 0 * * * *")
                .run(context -> assertThat(context.getBean(NoShowProperties.class).cron()).isEqualTo("0 0 * * * *"));
    }

    @Test
    void zeroBatchSizeRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.no-show.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
    }

    @Test
    void zeroTimeBudgetRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.no-show.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }
}
