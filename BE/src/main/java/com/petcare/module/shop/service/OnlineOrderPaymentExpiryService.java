package com.petcare.module.shop.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderRef;
import com.petcare.module.payment.api.PaymentTransactionApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineCancelType;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.module.shop.repository.OnlineOrderRepository.DueRow;

/**
 * ST10 — hủy đơn online quá hạn thanh toán (Đơn hàng online#4, BR-BH-12; docs/adr/0146, cơ chế job: docs/adr/0007). Mỗi
 * đơn một {@code @Transactional}: khóa {@code online_orders FOR NO KEY UPDATE SKIP LOCKED} trước (đơn đang bị callback
 * thanh toán hoặc khách hủy giữ khóa thì bỏ qua, lượt sau làm; thua cuộc đua thì đọc lại thấy đã rời
 * {@code PENDING_PAYMENT}) → đọc dưới khóa, kiểm lại trạng thái và hạn → hủy Order ({@code ONLINE_EXPIRED}, không có
 * người hủy) → hủy giao dịch {@code PENDING} → nhả giữ hàng → ghi đơn → thông báo. Cùng thứ tự khóa và cùng hệ quả với
 * khách hủy (ADR-0145). Không {@code try/catch}: lỗi rollback cả đơn đó, job log và thử lại lượt sau.
 */
@Service
public class OnlineOrderPaymentExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OnlineOrderPaymentExpiryService.class);

    /** Lý do hệ thống ghi vào đơn, Order và thông báo (≤ 300 ký tự). */
    static final String EXPIRED_REASON = "Quá hạn thanh toán";

    /** Kết quả xử lý một đơn. */
    public enum Result {
        /** Đã hủy đơn, Order, giao dịch {@code PENDING}, nhả giữ hàng và ghi thông báo. */
        EXPIRED,
        /** Không làm gì: đơn đã rời {@code PENDING_PAYMENT}, chưa quá hạn, đã có giao dịch thành công, hoặc đang bị khóa. */
        SKIPPED
    }

    /** Khóa keyset của một đơn tới hạn. */
    public record DueOrder(long id, Instant paymentDueAt) {
    }

    private final OnlineOrderRepository onlineOrders;
    private final OnlineSalesOrderApi sales;
    private final PaymentTransactionApi payments;
    private final StockApi stock;
    private final OnlineOrderNotices notices;
    private final OnlineOrderTransitionHandler fsm;
    private final Clock clock;

    public OnlineOrderPaymentExpiryService(OnlineOrderRepository onlineOrders, OnlineSalesOrderApi sales,
            PaymentTransactionApi payments, StockApi stock, OnlineOrderNotices notices,
            OnlineOrderTransitionHandler fsm, Clock clock) {
        this.onlineOrders = onlineOrders;
        this.sales = sales;
        this.payments = payments;
        this.stock = stock;
        this.notices = notices;
        this.fsm = fsm;
        this.clock = clock;
    }

    /**
     * Tối đa {@code limit} đơn {@code PENDING_PAYMENT} có {@code payment_due_at ≤ now}, theo {@code (payment_due_at, id)};
     * {@code afterDue} null = lô đầu, ngược lại tiếp từ {@code (afterDue, afterId)} loại trừ. Không khóa.
     */
    @Transactional(readOnly = true)
    public List<DueOrder> findDue(Instant now, Instant afterDue, long afterId, int limit) {
        Objects.requireNonNull(now, "now");
        PageRequest page = PageRequest.of(0, limit);
        List<DueRow> rows = afterDue == null ? onlineOrders.findExpiredPayment(now, page)
                : onlineOrders.findExpiredPaymentAfter(now, afterDue, afterId, page);
        return rows.stream().map(r -> new DueOrder(r.getId(), r.getDueAt())).toList();
    }

    @Transactional
    public Result expire(long onlineOrderId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("expire cần transaction ghi, không được readOnly");
        }
        if (onlineOrders.lockPendingPaymentSkipLocked(onlineOrderId).isEmpty()) {
            return Result.SKIPPED;
        }
        OnlineOrder order = onlineOrders.findById(onlineOrderId).orElseThrow(() -> new IllegalStateException(
                "Đơn online #" + onlineOrderId + " đã khóa nhưng không đọc được"));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (order.getStatus() != OnlineOrderStatus.PENDING_PAYMENT || order.getPaymentDueAt().isAfter(now)) {
            return Result.SKIPPED;
        }
        if (payments.hasSucceededForOnlineOrder(order.getId())) {
            // tiền đã thu mà đơn chưa sang PAID: không hủy (khách hủy cũng bị 409); chuyển PAID là việc của thanh toán
            log.warn("ONLINE_ORDER_EXPIRY_SKIPPED_PAID onlineOrderId={}", order.getId());
            return Result.SKIPPED;
        }
        fsm.validateTransition(order.getStatus(), OnlineOrderStatus.CANCELLED);
        // orders → payment_transactions → stock_reservations (ADR-0145)
        sales.cancel(order.getOrderId(), OnlineCancelType.EXPIRED, null, EXPIRED_REASON);
        payments.cancelPendingForOnlineOrder(order.getId());
        stock.release(new OrderRef(order.getId(), order.getOrderId()));
        order.expire(EXPIRED_REASON, now);
        onlineOrders.saveAndFlush(order);
        notices.cancelled(order, EXPIRED_REASON);
        return Result.EXPIRED;
    }
}
