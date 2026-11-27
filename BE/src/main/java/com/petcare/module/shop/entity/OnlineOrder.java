package com.petcare.module.shop.entity;

import java.time.Instant;

import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code online_orders} (ROOT, FSM #14, V44): đơn hàng online. Task 117 chỉ tạo đơn ở {@code PENDING_PAYMENT}
 * ({@link #place}); các chuyển trạng thái khác thuộc task 125 / 126 / 127. Địa chỉ giao và phí vận chuyển là bản chụp
 * lúc đặt (BR-BH-08, BR-SP-11, BR-QT-13).
 */
@Getter
@Entity
@Table(name = "online_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnlineOrder extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OnlineOrderStatus status;

    @Column(name = "receiver_name", nullable = false, length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 15)
    private String receiverPhone;

    @Column(name = "ship_address", nullable = false, length = 300)
    private String shipAddress;

    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;

    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "tracking_code", length = 100)
    private String trackingCode;

    @Column(name = "redelivery_count", nullable = false)
    private short redeliveryCount;

    @Column(name = "payment_due_at", nullable = false)
    private Instant paymentDueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    /** Tài khoản đã hủy; {@code null} khi hệ thống hủy (ST10). Chỉ có ở đơn {@code CANCELLED}. */
    @Column(name = "cancelled_by")
    private Long cancelledBy;

    /** Lý do giao thất bại gần nhất (V58, ADR-0137 D2); giữ nguyên sau khi giao lại; không có trong response hợp đồng. */
    @Column(name = "delivery_failure_reason", length = 500)
    private String deliveryFailureReason;

    /** Đơn hàng online#5 (PAID → PROCESSING): lễ tân bắt đầu xử lý; caller đã kiểm trạng thái và khóa dòng đơn. */
    public void startProcessing() {
        this.status = OnlineOrderStatus.PROCESSING;
    }

    /** Đơn hàng online#6 (PROCESSING → SHIPPED): bàn giao vận chuyển; caller đã kiểm trạng thái và khóa dòng đơn. */
    public void ship(String carrier, String trackingCode, Instant now) {
        this.status = OnlineOrderStatus.SHIPPED;
        this.carrier = carrier;
        this.trackingCode = trackingCode;
        this.shippedAt = now;
    }

    /** Đơn hàng online#7 (SHIPPED → DELIVERED): bắt đầu đếm hạn tự xác nhận (BR-BH-12). */
    public void markDelivered(Instant now) {
        this.status = OnlineOrderStatus.DELIVERED;
        this.deliveredAt = now;
    }

    /** Đơn hàng online#8 (SHIPPED → DELIVERY_FAILED): ghi lý do giao thất bại gần nhất. */
    public void failDelivery(String reason) {
        this.status = OnlineOrderStatus.DELIVERY_FAILED;
        this.deliveryFailureReason = reason;
    }

    /** Đơn hàng online#9 (DELIVERY_FAILED → SHIPPED): giao lại, thêm một lần, thay đơn vị vận chuyển / mã vận đơn. */
    public void redeliver(String carrier, String trackingCode, Instant now) {
        this.status = OnlineOrderStatus.SHIPPED;
        this.carrier = carrier;
        this.trackingCode = trackingCode;
        this.shippedAt = now;
        this.redeliveryCount++;
    }

    /** Đơn hàng online#3 (PENDING_PAYMENT → CANCELLED): khách hủy; caller đã kiểm trạng thái và khóa dòng đơn. */
    public void cancelByCustomer(Long accountId, String reason, Instant now) {
        this.status = OnlineOrderStatus.CANCELLED;
        this.cancelledBy = accountId;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    /** Đơn hàng online#4 (PENDING_PAYMENT → CANCELLED): ST10 hủy đơn quá hạn thanh toán; {@code cancelled_by} để trống (hệ thống). */
    public void expire(String reason, Instant now) {
        this.status = OnlineOrderStatus.CANCELLED;
        this.cancelledBy = null;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    /** Đơn hàng online#11 (DELIVERED → COMPLETED): khách xác nhận đã nhận; caller đã kiểm trạng thái và khóa dòng đơn. */
    public void confirmReceived(Instant now) {
        this.status = OnlineOrderStatus.COMPLETED;
        this.completedAt = now;
    }

    /** Đơn hàng online#1 (— → PENDING_PAYMENT): đơn mới, đang giữ hàng, chờ thanh toán trước {@code paymentDueAt}. */
    public static OnlineOrder place(String code, Long orderId, Long customerId, Long branchId, String receiverName,
            String receiverPhone, String shipAddress, long shippingFee, Instant paymentDueAt) {
        OnlineOrder order = new OnlineOrder();
        order.code = code;
        order.orderId = orderId;
        order.customerId = customerId;
        order.branchId = branchId;
        order.status = OnlineOrderStatus.PENDING_PAYMENT;
        order.receiverName = receiverName;
        order.receiverPhone = receiverPhone;
        order.shipAddress = shipAddress;
        order.shippingFee = shippingFee;
        order.redeliveryCount = 0;
        order.paymentDueAt = paymentDueAt;
        return order;
    }
}
