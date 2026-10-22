package com.petcare.module.sales.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.exception.OrderAmountOverflowException;
import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code orders} (erd §8, ROOT, SM #5). Tên lớp tránh {@code Order}: từ khóa JPQL. Chuyển trạng thái do service
 * kiểm bằng {@code OrderTransitionHandler} trước khi gọi các method ở đây; caller giữ khóa dòng Order
 * ({@code FOR NO KEY UPDATE}) từ trước khi đọc entity (docs/adr/0044). {@code totalAmount} luôn tính lại từ các dòng
 * ({@code addExact}), không cộng dồn chênh lệch.
 */
@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrder extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private OrderSource source;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "visit_id", updatable = false)
    private Long visitId;

    @Column(name = "boarding_booking_id", updatable = false)
    private Long boardingBookingId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "pending_at")
    private Instant pendingAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_type")
    private OrderCancelType cancelType;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id")
    private List<OrderLine> lines = new ArrayList<>();

    /** Order#1: Order nguồn VISIT ở {@code OPEN}. */
    public static SalesOrder openForVisit(String code, Long branchId, Long customerId, Long visitId, Long createdBy) {
        SalesOrder order = new SalesOrder();
        order.code = code;
        order.source = OrderSource.VISIT;
        order.branchId = branchId;
        order.customerId = customerId;
        order.visitId = visitId;
        order.createdBy = createdBy;
        order.status = OrderStatus.OPEN;
        return order;
    }

    /** Order#2: Order bán lẻ ở {@code OPEN}, không gắn Visit / đặt chỗ (BR-BH-01). */
    public static SalesOrder openRetail(String code, Long branchId, Long customerId, Long createdBy) {
        SalesOrder order = new SalesOrder();
        order.code = code;
        order.source = OrderSource.RETAIL;
        order.branchId = branchId;
        order.customerId = customerId;
        order.createdBy = createdBy;
        order.status = OrderStatus.OPEN;
        return order;
    }

    public void addLine(OrderLine line) {
        lines.add(line);
        try {
            recalculateTotal();
        } catch (OrderAmountOverflowException e) {
            lines.remove(line);
            throw e;
        }
    }

    public void removeLine(OrderLine line) {
        lines.remove(line);
        recalculateTotal();
    }

    /** Gọi sau khi sửa một dòng (số lượng, dịch vụ). */
    public void recalculateTotal() {
        long sum = 0;
        try {
            for (OrderLine line : lines) {
                sum = Math.addExact(sum, line.getLineTotal());
            }
        } catch (ArithmeticException e) {
            throw new OrderAmountOverflowException();
        }
        this.totalAmount = sum;
    }

    public Optional<OrderLine> findLine(Long lineId) {
        return lines.stream().filter(l -> lineId.equals(l.getId())).findFirst();
    }

    public void markPending(Instant now) {
        this.status = OrderStatus.PENDING;
        this.pendingAt = now;
    }

    /** Order#8: thu tiền. Caller đã kiểm {@code PENDING} và giữ khóa dòng Order. */
    public void markPaid(Long paymentId, Instant now) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = now;
    }

    /** Hủy Order: lý do chỉ bắt buộc khi hủy từ {@code PENDING} (caller kiểm). */
    public void cancel(OrderCancelType type, Long actorId, String reason, Instant now) {
        this.status = OrderStatus.CANCELLED;
        this.cancelType = type;
        this.cancelledBy = actorId;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }
}
