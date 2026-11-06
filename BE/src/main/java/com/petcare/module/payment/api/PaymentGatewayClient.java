package com.petcare.module.payment.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owner: payment (TG) · BE-2. Caller: payment (UC63, ST11, ST14), returns (ST21). Cổng thanh toán trung lập (BR-TG-11):
 * hệ thống chỉ làm việc với cổng qua interface này; mọi tham số riêng của cổng (VNPay…) được ánh xạ về dạng chung ở lớp
 * tích hợp. Khóa bí mật và địa chỉ cổng ở biến môi trường, không bao giờ đi qua interface (BR-TG-07).
 * <p>
 * Mới ở T55c (docs/adr/0117, docs/06 §6b hàng 8): chỉ khai báo và {@code PaymentGatewayClientPlaceholder} ném
 * {@link UnsupportedOperationException}. Bản cài (VNPay sandbox hoặc {@code FakeGatewayClient} khi chưa có sandbox) ở T65;
 * chữ ký dưới đây là đề xuất của T55c và chỉ đổi qua ADR của T65.
 */
public interface PaymentGatewayClient {

    /** Trạng thái giao dịch phía cổng. {@code PENDING}: cổng chưa có kết quả (ST11 giữ giao dịch {@code PENDING}). */
    enum GatewayStatus { SUCCEEDED, FAILED, PENDING }

    /** Kết quả một lần hoàn tiền: {@code TEMPORARY_ERROR} được thử lại, {@code REJECTED} thì không (BR-TG-14). */
    enum RefundOutcome { SUCCEEDED, REJECTED, TEMPORARY_ERROR }

    /** {@code txnCode} = {@code payment_transactions.code} (mã tham chiếu gửi cổng); {@code amount} tính bằng đồng. */
    record SessionRequest(String txnCode, long amount, String orderInfo, Instant expiresAt, String clientIp) {}

    record PaymentSession(String redirectUrl) {}

    /** Giao dịch như cổng báo (qua callback, truy vấn hoặc báo cáo đối soát). */
    record GatewayTransaction(String txnCode, String gatewayTxnId, long amount, GatewayStatus status) {}

    /** {@code refundCode} = {@code refunds.code}, dùng làm khóa idempotent (BR-TG-14). */
    record RefundRequest(String refundCode, String txnCode, String gatewayTxnId, long amount, String reason) {}

    record RefundResult(RefundOutcome outcome, String gatewayRefundId, String failureReason) {}

    /** Tạo phiên thanh toán, trả địa chỉ chuyển hướng (BR-TG-06, Giao dịch#1). Bản thật ở T65. */
    PaymentSession createSession(SessionRequest request);

    /**
     * Xác thực chữ ký callback (BR-TG-07) và đọc kết quả. Chữ ký sai thì {@link Optional#empty()}; bên gọi trả 400 và
     * không ghi gì ngoài log. {@code parameters} là các tham số thô của callback. Bản thật ở T65.
     */
    Optional<GatewayTransaction> verifyCallback(Map<String, String> parameters);

    /** Truy vấn giao dịch theo mã tham chiếu (ST11, BR-TG-09); cổng không biết giao dịch thì rỗng. Bản thật ở T65. */
    Optional<GatewayTransaction> queryTransaction(String txnCode);

    /** Hoàn tiền (ST21, BR-TG-14), idempotent theo {@code refundCode}. Bản thật trước 30/11 (T73), cùng bản cài ở T65. */
    RefundResult refund(RefundRequest request);

    /** Báo cáo giao dịch của một ngày để đối soát (ST14, BR-TG-10). Bản thật ở T65. */
    List<GatewayTransaction> fetchSettlementReport(LocalDate date);
}
