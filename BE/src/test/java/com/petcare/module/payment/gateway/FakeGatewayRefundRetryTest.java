package com.petcare.module.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.petcare.module.payment.api.PaymentGatewayClient.RefundOutcome;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundRequest;

class FakeGatewayRefundRetryTest {

    @Test
    void temporaryFailureDoesNotPreventSuccessfulRetryWithSameCode() {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        FakeGatewayClient fake = new FakeGatewayClient(
                new PaymentProperties("FAKE", null, null, null, null, null, null), Clock.fixed(now, ZoneOffset.UTC));
        RefundRequest retryable = new RefundRequest("HT-001", "GD-001", "GW-001", 40_000, "IT", now);
        RefundRequest rejected = new RefundRequest("HT-002", "GD-001", "GW-001", 40_000, "IT", now);

        fake.setRefundOutcome(RefundOutcome.TEMPORARY_ERROR);
        assertThat(fake.refund(retryable).outcome()).isEqualTo(RefundOutcome.TEMPORARY_ERROR);
        fake.setRefundOutcome(RefundOutcome.SUCCEEDED);
        var successful = fake.refund(retryable);
        assertThat(successful.outcome()).isEqualTo(RefundOutcome.SUCCEEDED);
        assertThat(successful.gatewayRefundId()).isEqualTo("FAKE-REFUND-HT-001");

        fake.setRefundOutcome(RefundOutcome.REJECTED);
        var terminalRejection = fake.refund(rejected);
        assertThat(terminalRejection.outcome()).isEqualTo(RefundOutcome.REJECTED);
        assertThat(fake.refund(retryable)).isEqualTo(successful);
        fake.setRefundOutcome(RefundOutcome.SUCCEEDED);
        assertThat(fake.refund(rejected)).isEqualTo(terminalRejection);
    }
}
