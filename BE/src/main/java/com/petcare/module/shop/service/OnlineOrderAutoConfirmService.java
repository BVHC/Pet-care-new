package com.petcare.module.shop.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.returns.api.ReturnRequestQueryApi;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.module.shop.repository.OnlineOrderRepository.DueRow;

/**
 * ST12 — tự xác nhận đã nhận (Đơn hàng online#12, BR-BH-12; docs/adr/0146, cơ chế job: docs/adr/0007): đơn {@code DELIVERED}
 * quá [CFG] {@code shop.auto_confirm_days} ngày kể từ {@code delivered_at} mà không có yêu cầu trả hàng đang mở thì sang
 * {@code COMPLETED}. Số ngày đọc lúc chạy (áp từ lần kiểm sau, ADR-0057). Mỗi đơn một {@code @Transactional}: khóa
 * {@code online_orders FOR NO KEY UPDATE SKIP LOCKED} → đọc dưới khóa, kiểm lại hạn → hỏi yêu cầu trả hàng đang mở
 * ({@link ReturnRequestQueryApi}) → ghi. Không thông báo (08 §B không đòi). Không {@code try/catch}.
 */
@Service
public class OnlineOrderAutoConfirmService {

    /** Kết quả xử lý một đơn. */
    public enum Result {
        /** Đã chuyển {@code COMPLETED}. */
        CONFIRMED,
        /** Không làm gì: đã rời {@code DELIVERED}, chưa quá hạn, có yêu cầu trả hàng đang mở, hoặc đang bị khóa. */
        SKIPPED
    }

    /** Khóa keyset của một đơn tới hạn. */
    public record DueOrder(long id, Instant deliveredAt) {
    }

    private final OnlineOrderRepository onlineOrders;
    private final ReturnRequestQueryApi returns;
    private final SystemConfigApi config;
    private final OnlineOrderTransitionHandler fsm;
    private final Clock clock;

    public OnlineOrderAutoConfirmService(OnlineOrderRepository onlineOrders, ReturnRequestQueryApi returns,
            SystemConfigApi config, OnlineOrderTransitionHandler fsm, Clock clock) {
        this.onlineOrders = onlineOrders;
        this.returns = returns;
        this.config = config;
        this.fsm = fsm;
        this.clock = clock;
    }

    /**
     * Tối đa {@code limit} đơn {@code DELIVERED} có {@code delivered_at ≤ now − auto_confirm_days}, theo
     * {@code (delivered_at, id)}; {@code afterAt} null = lô đầu. Không khóa; đơn có yêu cầu trả hàng đang mở vẫn được
     * liệt kê và bị bỏ qua ở {@link #confirm} (bảng của module khác, không join).
     */
    @Transactional(readOnly = true)
    public List<DueOrder> findDue(Instant now, Instant afterAt, long afterId, int limit) {
        Objects.requireNonNull(now, "now");
        Instant cutoff = cutoff(now);
        PageRequest page = PageRequest.of(0, limit);
        List<DueRow> rows = afterAt == null ? onlineOrders.findDeliveredBefore(cutoff, page)
                : onlineOrders.findDeliveredBeforeAfter(cutoff, afterAt, afterId, page);
        return rows.stream().map(r -> new DueOrder(r.getId(), r.getDueAt())).toList();
    }

    @Transactional
    public Result confirm(long onlineOrderId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("confirm cần transaction ghi, không được readOnly");
        }
        if (onlineOrders.lockDeliveredSkipLocked(onlineOrderId).isEmpty()) {
            return Result.SKIPPED;
        }
        OnlineOrder order = onlineOrders.findById(onlineOrderId).orElseThrow(() -> new IllegalStateException(
                "Đơn online #" + onlineOrderId + " đã khóa nhưng không đọc được"));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (order.getStatus() != OnlineOrderStatus.DELIVERED || order.getDeliveredAt() == null
                || order.getDeliveredAt().isAfter(cutoff(now))) {
            return Result.SKIPPED;
        }
        if (returns.hasOpenRequestForOrder(order.getOrderId())) {
            return Result.SKIPPED;
        }
        fsm.validateTransition(order.getStatus(), OnlineOrderStatus.COMPLETED);
        order.confirmReceived(now);
        onlineOrders.saveAndFlush(order);
        return Result.CONFIRMED;
    }

    private Instant cutoff(Instant now) {
        return now.minus(config.getInt(ConfigKey.SHOP_AUTO_CONFIRM_DAYS), ChronoUnit.DAYS);
    }
}
