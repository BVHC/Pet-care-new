package com.petcare.module.payment.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.petcare.module.payment.api.PaymentGatewayClient;

/**
 * {@link PaymentGatewayClient} giả (task 127, docs/payment-gateway-setup.md §6), chọn bằng {@code PAYMENT_GATEWAY=FAKE}
 * (mặc định): không gọi mạng, dùng đúng thuật toán ký của VNPay ({@link VnpayProtocol}) với khóa cấu hình, nên chữ ký giả
 * và chữ ký thật đi qua cùng một đường kiểm. Dùng cho test và demo cục bộ khi chưa có tài khoản sandbox.
 * <ul>
 *   <li>{@link #createSession}: địa chỉ trang giả lập của FE ({@code /payment/mock-gateway?code=&amount=}) kèm các tham số
 *       {@code vnp_*} đã ký; kết quả xác định theo đầu vào (không ngẫu nhiên).</li>
 *   <li>{@link #verifyCallback}: như VNPay. {@link #signedCallback} dựng callback hợp lệ cho test / trang giả lập.</li>
 *   <li>{@link #queryTransaction} và {@link #fetchSettlementReport}: trả từ bộ nhớ, test đặt bằng {@link #setTransaction}
 *       / {@link #recordSettlement}.</li>
 *   <li>{@link #refund}: idempotent theo {@code refundCode}; kết quả mặc định {@code SUCCEEDED}, đổi bằng
 *       {@link #setRefundOutcome}.</li>
 * </ul>
 * Không phải mã sản xuất: bản cài VNPay nằm ở {@link VnpayGatewayClient}.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "FAKE", matchIfMissing = true)
public class FakeGatewayClient implements PaymentGatewayClient {

    /** Khóa dùng khi {@code PAYMENT_SECRET} trống (chạy cục bộ không cấu hình). Không dùng cho cổng thật. */
    static final String DEFAULT_SECRET = "fake-gateway-secret-local-only";
    static final String DEFAULT_MOCK_URL = "http://localhost:5173/payment/mock-gateway";

    private final PaymentProperties props;
    private final Clock clock;
    private final Map<String, GatewayTransaction> known = new ConcurrentHashMap<>();
    private final Map<LocalDate, List<GatewayTransaction>> settlements = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();
    private volatile RefundOutcome refundOutcome = RefundOutcome.SUCCEEDED;

    public FakeGatewayClient(PaymentProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    private String secret() {
        return props.secret() == null || props.secret().isBlank() ? DEFAULT_SECRET : props.secret();
    }

    private String merchantCode() {
        return props.merchantCode() == null || props.merchantCode().isBlank() ? "FAKEMERCHANT" : props.merchantCode();
    }

    @Override
    public PaymentSession createSession(SessionRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", merchantCode());
        params.put("vnp_Amount", String.valueOf(Math.multiplyExact(request.amount(), 100L)));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", request.txnCode());
        params.put("vnp_OrderInfo", VnpayGatewayClient.ascii(request.orderInfo()));
        params.put("vnp_ExpireDate", VnpayProtocol.formatDate(request.expiresAt()));
        params.put("vnp_ReturnUrl", props.returnUrl());
        String base = props.baseUrl() == null || props.baseUrl().isBlank() ? DEFAULT_MOCK_URL : props.baseUrl();
        // code / amount ngoài chữ ký: trang giả lập của FE đọc hai tham số này (FE/src/pages/customer/PaymentGatewayMockPage.tsx)
        return new PaymentSession(VnpayProtocol.signedUrl(base + (base.contains("?") ? "&" : "?") + "code="
                + request.txnCode() + "&amount=" + request.amount(), params, secret()));
    }

    @Override
    public Optional<GatewayTransaction> verifyCallback(Map<String, String> parameters) {
        return VnpayProtocol.parseCallback(parameters, secret());
    }

    @Override
    public Optional<GatewayTransaction> queryTransaction(String txnCode) {
        return Optional.ofNullable(known.get(txnCode));
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        RefundResult terminal = refunds.computeIfAbsent(request.refundCode(), code -> switch (refundOutcome) {
            case SUCCEEDED -> new RefundResult(RefundOutcome.SUCCEEDED, "FAKE-REFUND-" + code, null);
            case REJECTED -> new RefundResult(RefundOutcome.REJECTED, null, "Fake: từ chối hoàn tiền");
            case TEMPORARY_ERROR -> null;
        });
        return terminal == null ? new RefundResult(RefundOutcome.TEMPORARY_ERROR, null, "Fake: lỗi tạm") : terminal;
    }

    @Override
    public List<GatewayTransaction> fetchSettlementReport(LocalDate date) {
        return new ArrayList<>(settlements.getOrDefault(date, List.of()));
    }

    // ---------------------------------------------------------------- tiện cho test / demo

    /** Callback hợp lệ như cổng gửi: {@code status} {@code SUCCEEDED} = mã 00, {@code FAILED} = 24 (khách hủy), {@code PENDING} = 01. */
    public Map<String, String> signedCallback(String txnCode, long amount, GatewayStatus status, String gatewayTxnId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", merchantCode());
        params.put("vnp_Amount", String.valueOf(Math.multiplyExact(amount, 100L)));
        params.put("vnp_TxnRef", txnCode);
        params.put("vnp_TransactionNo", gatewayTxnId);
        params.put("vnp_ResponseCode", status == GatewayStatus.SUCCEEDED ? "00" : status == GatewayStatus.PENDING ? "00" : "24");
        params.put("vnp_TransactionStatus", status == GatewayStatus.SUCCEEDED ? "00" : status == GatewayStatus.PENDING ? "01" : "02");
        params.put("vnp_PayDate", VnpayProtocol.formatDate(Instant.now(clock)));
        params.put(VnpayProtocol.SECURE_HASH, VnpayProtocol.sign(params, secret()));
        return params;
    }

    /** Đặt kết quả mà {@link #queryTransaction} trả cho {@code transaction.txnCode()}. */
    public void setTransaction(GatewayTransaction transaction) {
        known.put(transaction.txnCode(), transaction);
    }

    /** Thêm một giao dịch vào báo cáo đối soát của {@code date}. */
    public void recordSettlement(LocalDate date, GatewayTransaction transaction) {
        settlements.computeIfAbsent(date, d -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(transaction);
    }

    /** Kết quả cho mã hoàn tiền chưa có kết quả cuối. */
    public void setRefundOutcome(RefundOutcome outcome) {
        this.refundOutcome = outcome;
    }

    /** Xóa mọi trạng thái trong bộ nhớ. */
    public void reset() {
        known.clear();
        settlements.clear();
        refunds.clear();
        refundOutcome = RefundOutcome.SUCCEEDED;
    }
}
