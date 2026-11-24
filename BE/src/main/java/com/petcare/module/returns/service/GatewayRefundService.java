package com.petcare.module.returns.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.payment.api.PaymentGatewayClient;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundOutcome;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundRequest;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundResult;
import com.petcare.module.payment.api.PaymentGatewayException;
import com.petcare.module.returns.repository.GatewayRefundDao;
import com.petcare.module.returns.repository.GatewayRefundDao.DueRefund;
import com.petcare.module.returns.repository.GatewayRefundDao.OnlineOrder;
import com.petcare.module.returns.repository.GatewayRefundDao.OriginalPayment;

import lombok.RequiredArgsConstructor;

/** ST21 (BR-TG-14), giới hạn ở FAKE tới khi kiểm chứng cách hoàn một phần và idempotency của VNPay. */
@Service
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "FAKE", matchIfMissing = true)
@RequiredArgsConstructor
public class GatewayRefundService {

    private static final Duration RETRY_DELAY = Duration.ofMinutes(15);

    private final GatewayRefundDao refunds;
    private final PaymentGatewayClient gateway;
    private final SystemConfigApi config;
    private final NotificationApi notifications;
    private final StaffDirectoryApi staff;
    private final BranchQueryApi branches;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void process(long refundId) {
        Instant now = now();
        // Tuần tự cả các refund khác mã của cùng một đơn trước khi đọc tổng hoàn và gọi cổng.
        Long onlineOrderId = refunds.findOnlineOrder(refundId).orElse(null);
        OnlineOrder order = onlineOrderId == null ? null : refunds.lockOnlineOrder(onlineOrderId).orElse(null);
        DueRefund refund = refunds.lockDueRefund(refundId, now).orElse(null);
        if (refund == null) {
            return;
        }
        if (order == null || order.branchId() != refund.branchId()) {
            fail(refund, refund.attempts(), "Không xác định được đơn online gốc", null, now);
            return;
        }
        if (refund.returnRequestId() != null && !refunds.lockApprovedReturn(refund.returnRequestId())) {
            fail(refund, refund.attempts(), "Yêu cầu trả hàng không ở trạng thái đã duyệt", order, now);
            return;
        }

        List<OriginalPayment> payments = refunds.findSuccessfulPayments(onlineOrderId);
        if (payments.size() != 1) {
            fail(refund, refund.attempts(), "Không xác định được một giao dịch thanh toán gốc", order, now);
            return;
        }
        OriginalPayment payment = payments.getFirst();
        if (!"FAKE".equalsIgnoreCase(payment.gateway()) || payment.gatewayTxnId() == null
                || payment.gatewayTxnId().isBlank() || !refunds.totalRefundsWithinCharge(onlineOrderId, payment.amount())) {
            fail(refund, refund.attempts(), "Giao dịch gốc hoặc tổng số tiền hoàn không hợp lệ", order, now);
            return;
        }
        int limit = config.getInt(ConfigKey.PAYMENT_REFUND_MAX_ATTEMPTS);
        if (refund.attempts() >= limit) {
            fail(refund, refund.attempts(), "Đã hết số lần thử hoàn tiền", order, now);
            return;
        }

        int attempts = refund.attempts() + 1;
        String reason = "Hoàn tiền " + refund.code();
        RefundResult result;
        try {
            result = gateway.refund(new RefundRequest(refund.code(), payment.code(), payment.gatewayTxnId(),
                    refund.amount(), reason, payment.createdAt()));
        } catch (PaymentGatewayException ex) {
            result = new RefundResult(RefundOutcome.TEMPORARY_ERROR, null, null);
        }
        Instant finishedAt = now();
        switch (result.outcome()) {
            case SUCCEEDED -> refunds.succeed(refund, attempts, result.gatewayRefundId(), finishedAt);
            case REJECTED -> fail(refund, attempts, "Cổng thanh toán từ chối hoàn tiền", order, finishedAt);
            case TEMPORARY_ERROR -> {
                String failure = "Lỗi tạm thời từ cổng thanh toán";
                if (attempts >= limit) {
                    fail(refund, attempts, failure, order, finishedAt);
                } else {
                    refunds.retry(refund.id(), attempts, failure, finishedAt.plus(RETRY_DELAY), finishedAt);
                }
            }
        }
    }

    private void fail(DueRefund refund, int attempts, String reason, OnlineOrder order, Instant now) {
        refunds.fail(refund.id(), attempts, reason, now);
        List<Long> managers = staff.findActiveStaffIds(refund.branchId(), Role.BRANCH_MANAGER);
        if (managers.isEmpty()) {
            return;
        }
        String branchName = branches.findBranch(refund.branchId()).orElseThrow().name();
        Map<String, Object> payload = Map.of("ma_hoan_tien", refund.code(), "so_tien_hoan", refund.amount(),
                "ma_don", order == null ? "Không xác định" : order.code(), "chi_nhanh", branchName);
        for (Long manager : managers) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.REFUND_FAILED_APP, Channel.IN_APP,
                    manager, null, payload, null));
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
