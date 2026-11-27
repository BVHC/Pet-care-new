package com.petcare.module.payment.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owner: payment (TG) · BE-2. Caller: payment (UC63, ST11, ST14). Cổng thanh toán trung lập (BR-TG-11):
 * hệ thống chỉ làm việc với cổng qua interface này; mọi tham số riêng của cổng (VNPay…) được ánh xạ về dạng chung ở lớp
 * tích hợp. Khóa bí mật và địa chỉ cổng ở biến môi trường, không bao giờ đi qua interface (BR-TG-07).
 * <p>
 * Khai báo ở T55c (docs/adr/0117, docs/06 §6b hàng 8). Bản cài từ task 127: {@code VnpayGatewayClient} ({@code
 * PAYMENT_GATEWAY=VNPAY}) và {@code FakeGatewayClient} (mặc định {@code FAKE}, không gọi mạng), đúng một bean theo cấu
 * hình. Task 127 thêm {@link #queryTransaction(String, Instant)}: VNPay đòi thời điểm tạo giao dịch
 * ({@code vnp_TransactionDate}) khi truy vấn (ADR của task 127). Hoàn tiền đã bỏ (ADR-0156).
 */
public interface PaymentGatewayClient {

    /** Trạng thái giao dịch phía cổng. {@code PENDING}: cổng chưa có kết quả (ST11 giữ giao dịch {@code PENDING}). */
    enum GatewayStatus { SUCCEEDED, FAILED, PENDING }

    /** {@code txnCode} = {@code payment_transactions.code} (mã tham chiếu gửi cổng); {@code amount} tính bằng đồng. */
    record SessionRequest(String txnCode, long amount, String orderInfo, Instant expiresAt, String clientIp) {}

    record PaymentSession(String redirectUrl) {}

    /** Giao dịch như cổng báo (qua callback, truy vấn hoặc báo cáo đối soát). */
    record GatewayTransaction(String txnCode, String gatewayTxnId, long amount, GatewayStatus status) {}

    /** Tạo phiên thanh toán, trả địa chỉ chuyển hướng (BR-TG-06, Giao dịch#1). */
    PaymentSession createSession(SessionRequest request);

    /**
     * Xác thực chữ ký callback (BR-TG-07) và đọc kết quả. Chữ ký sai thì {@link Optional#empty()}; bên gọi trả 400 và
     * không ghi gì ngoài log. {@code parameters} là các tham số thô của callback.
     */
    Optional<GatewayTransaction> verifyCallback(Map<String, String> parameters);

    /**
     * Truy vấn giao dịch theo mã tham chiếu (ST11, BR-TG-09); cổng không biết giao dịch thì rỗng; cổng không trả lời /
     * trả lời sai thì {@link PaymentGatewayException} (giữ {@code PENDING}).
     */
    Optional<GatewayTransaction> queryTransaction(String txnCode);

    /**
     * Như {@link #queryTransaction(String)} kèm thời điểm tạo giao dịch ({@code payment_transactions.created_at}) mà VNPay
     * đòi; bản mặc định bỏ qua thời điểm. Bên gọi (ST11, task 135) nên dùng method này.
     */
    default Optional<GatewayTransaction> queryTransaction(String txnCode, Instant txnCreatedAt) {
        return queryTransaction(txnCode);
    }

    /** Báo cáo giao dịch của một ngày để đối soát (ST14, BR-TG-10). */
    List<GatewayTransaction> fetchSettlementReport(LocalDate date);
}
