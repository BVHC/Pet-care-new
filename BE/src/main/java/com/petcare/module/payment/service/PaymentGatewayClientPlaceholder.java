package com.petcare.module.payment.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.petcare.module.payment.api.PaymentGatewayClient;

/**
 * Placeholder của {@link PaymentGatewayClient} (T55c, docs/06 §1, docs/adr/0117): ném {@link UnsupportedOperationException}
 * thay vì trả giá trị giả (một "giao dịch thành công" giả sẽ đánh dấu đơn đã trả). <b>Xóa lớp này</b> khi T65 cài
 * {@code VnpayGatewayClient} / {@code FakeGatewayClient} (hai bean cùng interface làm hỏng việc tiêm).
 */
@Component
public class PaymentGatewayClientPlaceholder implements PaymentGatewayClient {

    @Override
    public PaymentSession createSession(SessionRequest request) {
        throw placeholder("createSession", "T65");
    }

    @Override
    public Optional<GatewayTransaction> verifyCallback(Map<String, String> parameters) {
        throw placeholder("verifyCallback", "T65");
    }

    @Override
    public Optional<GatewayTransaction> queryTransaction(String txnCode) {
        throw placeholder("queryTransaction", "T65");
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        throw placeholder("refund", "T73");
    }

    @Override
    public List<GatewayTransaction> fetchSettlementReport(LocalDate date) {
        throw placeholder("fetchSettlementReport", "T65");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("PaymentGatewayClient." + method
                + " chưa cài (placeholder T55c, docs/06 §6b); bản thật ở " + task);
    }
}
