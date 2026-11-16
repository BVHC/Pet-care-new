package com.petcare.module.shop.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.entity.OnlineOrder;

/** Bảng {@code online_orders} (docs/adr/0144, 0145). */
public interface OnlineOrderRepository extends JpaRepository<OnlineOrder, Long> {

    /** Chủ đơn, đọc không khóa và không nạp entity ({@code customer_id} bất biến): kiểm quyền trước khi khóa (404 cho đơn người khác). */
    @Query("SELECT o.customerId FROM OnlineOrder o WHERE o.id = :id")
    Optional<Long> findCustomerIdById(@Param("id") Long id);

    /**
     * Khóa dòng đơn {@code FOR NO KEY UPDATE} <b>trước</b> khi đọc entity (khóa đầu tiên của mọi ghi lên đơn online,
     * ADR-0145). Câu native trả id nên persistence context không giữ bản cũ. {@code MANDATORY}: ngoài transaction khóa
     * nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM online_orders WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    /** {@code delivered_at} của đơn ứng với Order {@code orders.id}; rỗng khi không có đơn hoặc chưa giao. */
    @Query("SELECT o.deliveredAt FROM OnlineOrder o WHERE o.orderId = :orderId AND o.deliveredAt IS NOT NULL")
    Optional<Instant> findDeliveredAtByOrderId(@Param("orderId") Long orderId);

    /** Chi nhánh xử lý, đọc không khóa và không nạp entity ({@code branch_id} bất biến): kiểm phạm vi (403) trước khi khóa. */
    @Query("SELECT o.branchId FROM OnlineOrder o WHERE o.id = :id")
    Optional<Long> findBranchIdById(@Param("id") Long id);

    /** Khóa keyset của một đơn tới hạn (ST10 / ST12): id và mốc dùng để sắp xếp. */
    interface DueRow {
        Long getId();

        Instant getDueAt();
    }

    /**
     * ST10, lô đầu: đơn {@code PENDING_PAYMENT} có {@code payment_due_at ≤ now}, theo {@code (payment_due_at, id)}
     * ({@code ix_online_orders_pending_payment_due}); hằng trạng thái nằm trong câu để khớp chỉ mục từng phần. Không khóa.
     */
    @Query("""
            SELECT o.id AS id, o.paymentDueAt AS dueAt FROM OnlineOrder o
            WHERE o.status = com.petcare.module.shop.api.OnlineOrderStatus.PENDING_PAYMENT AND o.paymentDueAt <= :now
            ORDER BY o.paymentDueAt, o.id""")
    List<DueRow> findExpiredPayment(@Param("now") Instant now, Pageable limit);

    /** ST10, các lô sau: tiếp từ {@code (afterDue, afterId)} loại trừ. */
    @Query("""
            SELECT o.id AS id, o.paymentDueAt AS dueAt FROM OnlineOrder o
            WHERE o.status = com.petcare.module.shop.api.OnlineOrderStatus.PENDING_PAYMENT AND o.paymentDueAt <= :now
              AND (o.paymentDueAt > :afterDue OR (o.paymentDueAt = :afterDue AND o.id > :afterId))
            ORDER BY o.paymentDueAt, o.id""")
    List<DueRow> findExpiredPaymentAfter(@Param("now") Instant now, @Param("afterDue") Instant afterDue,
            @Param("afterId") long afterId, Pageable limit);

    /** ST12, lô đầu: đơn {@code DELIVERED} có {@code delivered_at ≤ cutoff}, theo {@code (delivered_at, id)} ({@code ix_online_orders_delivered_at}). */
    @Query("""
            SELECT o.id AS id, o.deliveredAt AS dueAt FROM OnlineOrder o
            WHERE o.status = com.petcare.module.shop.api.OnlineOrderStatus.DELIVERED AND o.deliveredAt <= :cutoff
            ORDER BY o.deliveredAt, o.id""")
    List<DueRow> findDeliveredBefore(@Param("cutoff") Instant cutoff, Pageable limit);

    /** ST12, các lô sau. */
    @Query("""
            SELECT o.id AS id, o.deliveredAt AS dueAt FROM OnlineOrder o
            WHERE o.status = com.petcare.module.shop.api.OnlineOrderStatus.DELIVERED AND o.deliveredAt <= :cutoff
              AND (o.deliveredAt > :afterAt OR (o.deliveredAt = :afterAt AND o.id > :afterId))
            ORDER BY o.deliveredAt, o.id""")
    List<DueRow> findDeliveredBeforeAfter(@Param("cutoff") Instant cutoff, @Param("afterAt") Instant afterAt,
            @Param("afterId") long afterId, Pageable limit);

    /**
     * ST10: khóa đơn còn {@code PENDING_PAYMENT} {@code FOR NO KEY UPDATE SKIP LOCKED} (khóa đầu tiên của ghi lên đơn
     * online, ADR-0145). Rỗng khi đơn đã rời trạng thái hoặc đang bị transaction khác giữ (callback thanh toán, khách
     * hủy): lượt sau làm tiếp. {@code MANDATORY}: ngoài transaction khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM online_orders WHERE id = :id AND status = 'PENDING_PAYMENT' "
            + "FOR NO KEY UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Long> lockPendingPaymentSkipLocked(@Param("id") Long id);

    /** ST12: như {@link #lockPendingPaymentSkipLocked} cho đơn còn {@code DELIVERED}. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM online_orders WHERE id = :id AND status = 'DELIVERED' "
            + "FOR NO KEY UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Long> lockDeliveredSkipLocked(@Param("id") Long id);

    /** Đơn của một chi nhánh theo tập trạng thái (task 126), phân trang; sắp xếp do {@code Pageable} (cũ nhất trước). */
    Page<OnlineOrder> findByBranchIdAndStatusIn(Long branchId, Collection<OnlineOrderStatus> statuses, Pageable pageable);

    /** Đơn của khách, phân trang; sắp xếp do {@code Pageable} (mới nhất trước, {@code ix_online_orders_customer_created}). */
    Page<OnlineOrder> findByCustomerId(Long customerId, Pageable pageable);

    Page<OnlineOrder> findByCustomerIdAndStatus(Long customerId, OnlineOrderStatus status, Pageable pageable);
}
