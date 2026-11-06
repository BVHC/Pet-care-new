package com.petcare.module.payment.service;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.payment.api.PaymentGatewayClient;

/**
 * Placeholder của {@code PaymentGatewayClient} (T55c, docs/06 §6b hàng 8, docs/adr/0117, 08 §C BR-TG-11): cả năm thao tác
 * ném lỗi. Quan trọng nhất là {@code verifyCallback} và {@code queryTransaction}: một placeholder trả "thành công" giả sẽ
 * đánh dấu đơn đã thanh toán.
 */
class PaymentGatewayClientPlaceholderTest {

    private final PaymentGatewayClientPlaceholder placeholder = new PaymentGatewayClientPlaceholder();

    @Test
    void createSessionThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.createSession(new PaymentGatewayClient.SessionRequest("TXN-1", 120_000L, "Don DH-1",
                Instant.parse("2026-11-06T03:15:00Z"), "127.0.0.1")), "PaymentGatewayClient.createSession", "T65");
    }

    @Test
    void verifyCallbackThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.verifyCallback(Map.of("a", "b")), "PaymentGatewayClient.verifyCallback", "T65");
    }

    @Test
    void queryTransactionThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.queryTransaction("TXN-1"), "PaymentGatewayClient.queryTransaction", "T65");
    }

    @Test
    void refundThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.refund(new PaymentGatewayClient.RefundRequest("HT-1", "TXN-1", "G-1", 90_000L, "Trả hàng")),
                "PaymentGatewayClient.refund", "T73");
    }

    @Test
    void fetchSettlementReportThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.fetchSettlementReport(LocalDate.of(2026, 11, 5)),
                "PaymentGatewayClient.fetchSettlementReport", "T65");
    }
}
