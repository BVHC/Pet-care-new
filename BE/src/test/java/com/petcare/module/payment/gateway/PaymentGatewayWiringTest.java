package com.petcare.module.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.petcare.module.payment.api.PaymentGatewayClient;

/**
 * Chọn bean theo {@code app.payment.gateway} (task 127): mỗi giá trị nối đúng một {@link PaymentGatewayClient}; thiếu giá
 * trị là {@code FAKE} (BE khởi động được khi chưa có khóa); {@code VNPAY} thiếu cấu hình thì không dựng được (nhanh lỗi).
 * Không cần Docker.
 */
class PaymentGatewayWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC))
            .withUserConfiguration(PaymentGatewayConfig.class, FakeGatewayClient.class, VnpayGatewayClient.class);

    @Test
    void fakeIsSelectedExplicitly() {
        runner.withPropertyValues("app.payment.gateway=FAKE").run(ctx -> {
            assertThat(ctx).hasSingleBean(PaymentGatewayClient.class);
            assertThat(ctx).hasSingleBean(FakeGatewayClient.class).doesNotHaveBean(VnpayGatewayClient.class);
        });
    }

    @Test
    void fakeIsTheDefaultWhenNothingIsConfigured() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PaymentGatewayClient.class);
            assertThat(ctx).hasSingleBean(FakeGatewayClient.class).doesNotHaveBean(VnpayGatewayClient.class);
        });
    }

    @Test
    void vnpayIsSelectedWithItsCredentials() {
        runner.withPropertyValues("app.payment.gateway=VNPAY", "app.payment.merchant-code=TESTCODE",
                "app.payment.secret=s3cret", "app.payment.return-url=http://localhost:5173/checkout/result").run(ctx -> {
                    assertThat(ctx).hasSingleBean(PaymentGatewayClient.class);
                    assertThat(ctx).hasSingleBean(VnpayGatewayClient.class).doesNotHaveBean(FakeGatewayClient.class);
                });
    }

    @Test
    void vnpayWithoutCredentialsFailsAtStartupWithoutLeakingAnything() {
        runner.withPropertyValues("app.payment.gateway=VNPAY").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }
}
