package com.petcare.module.sales.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.jobs.cashier-shift-auto-close} và {@code app.jobs.pending-order-digest} từ {@code application.yml} thật và
 * fail-fast khi giá trị sai (ST13; docs/adr/0064).
 */
class SalesJobPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CashierShiftAutoCloseProperties.class, PendingOrderDigestProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CashierShiftAutoCloseProperties close = context.getBean(CashierShiftAutoCloseProperties.class);
            assertThat(close.cron()).isEqualTo("0 4/5 * * * *");
            assertThat(close.batchSize()).isEqualTo(100);
            assertThat(close.timeBudget()).isEqualTo(Duration.ofSeconds(60));
            PendingOrderDigestProperties digest = context.getBean(PendingOrderDigestProperties.class);
            assertThat(digest.cron()).isEqualTo("0 50 23 * * *");
            assertThat(digest.timeBudget()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void cronCanBeOverriddenByEnvironmentVariable() {
        runner.withPropertyValues("CASHIER_SHIFT_AUTO_CLOSE_CRON=0 0 * * * *", "PENDING_ORDER_DIGEST_CRON=0 0 22 * * *")
                .run(context -> {
                    assertThat(context.getBean(CashierShiftAutoCloseProperties.class).cron())
                            .isEqualTo("0 0 * * * *");
                    assertThat(context.getBean(PendingOrderDigestProperties.class).cron()).isEqualTo("0 0 22 * * *");
                });
    }

    @Test
    void zeroBatchSizeAndTimeBudgetAreRejectedAtStartup() {
        runner.withPropertyValues("app.jobs.cashier-shift-auto-close.batch-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("batchSize"));
        runner.withPropertyValues("app.jobs.cashier-shift-auto-close.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
        runner.withPropertyValues("app.jobs.pending-order-digest.time-budget=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("timeBudget"));
    }
}
