package com.petcare.module.sales.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;

import jakarta.persistence.LockModeType;

/**
 * Order (docs/adr/0044). Mọi thao tác ghi khóa dòng Order {@code FOR NO KEY UPDATE} <b>trước</b> khi đọc entity; không
 * {@code findById} rồi mới khóa (persistence context cũ, ADR-0029 §4).
 */
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.id = :id")
    Optional<SalesOrder> findByIdForUpdate(@Param("id") Long id);

    /** Id và chi nhánh của Order, đọc không khóa và không nạp entity (thu tiền kiểm phạm vi trước khi khóa, docs/adr/0103). */
    interface BranchRow {
        Long getId();

        Long getBranchId();
    }

    @Query("SELECT o.id AS id, o.branchId AS branchId FROM SalesOrder o WHERE o.id IN :ids ORDER BY o.id")
    List<BranchRow> findBranchRows(@Param("ids") Collection<Long> ids);

    /** Chi nhánh của Order, đọc không khóa và không nạp entity: {@code branch_id} bất biến nên đọc trước khóa vẫn đúng. */
    @Query("SELECT o.branchId FROM SalesOrder o WHERE o.id = :id")
    Optional<Long> findBranchIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.visitId = :visitId")
    Optional<SalesOrder> findByVisitIdForUpdate(@Param("visitId") Long visitId);

    /**
     * Thu gộp (docs/adr/0064): khóa <b>mọi</b> Order của phiếu trong <b>một</b> câu theo id tăng dần, nên hai phiếu thu
     * chồng nhau không khóa ngược thứ tự. {@code MANDATORY}: gọi ngoài transaction thì khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.id IN :ids ORDER BY o.id")
    List<SalesOrder> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    /** Như {@link #findAllByIdForUpdate} nhưng không khóa: xem trước phiếu thu chỉ đọc (docs/adr/0064). */
    @Query("SELECT o FROM SalesOrder o WHERE o.id IN :ids ORDER BY o.id")
    List<SalesOrder> findAllByIdOrdered(@Param("ids") Collection<Long> ids);

    /** Order chờ thu của một khách tại một chi nhánh, chốt lâu nhất trước (dùng {@code ix_orders_customer_status}). */
    List<SalesOrder> findByCustomerIdAndBranchIdAndStatusOrderByPendingAtAscIdAsc(Long customerId, Long branchId,
            OrderStatus status);

    /** Chi nhánh có Order {@code PENDING} quá hạn: số Order và lúc chốt cũ nhất (ST13, docs/adr/0064). */
    interface BranchOverdue {
        Long getBranchId();

        long getOrderCount();

        java.time.Instant getOldestPendingAt();
    }

    /**
     * ST13: Order {@code PENDING} chốt trước {@code cutoff} theo chi nhánh, tăng dần theo id chi nhánh. Dùng
     * {@code ix_orders_branch_status_pending_at}. Cùng định nghĩa "quá hạn" với bộ lọc {@code pendingOverdue}.
     */
    @Query("SELECT o.branchId AS branchId, count(o) AS orderCount, min(o.pendingAt) AS oldestPendingAt "
            + "FROM SalesOrder o WHERE o.status = com.petcare.module.sales.entity.OrderStatus.PENDING "
            + "AND o.pendingAt < :cutoff GROUP BY o.branchId ORDER BY o.branchId")
    List<BranchOverdue> findOverdueByBranch(@Param("cutoff") java.time.Instant cutoff);

    /** Các Order của một phiếu thu (dùng {@code ix_orders_payment_id}, V19). */
    List<SalesOrder> findByPaymentIdOrderById(Long paymentId);

    @Query("SELECT o FROM SalesOrder o WHERE o.visitId = :visitId")
    Optional<SalesOrder> findByVisitId(@Param("visitId") Long visitId);

    /** Id Order chứa dòng (đọc không khóa): bước đầu để khóa Order rồi đọc lại dòng dưới khóa. */
    @Query("SELECT o.id FROM SalesOrder o JOIN o.lines l WHERE l.id = :lineId")
    Optional<Long> findIdByLineId(@Param("lineId") Long lineId);

    boolean existsByCustomerId(Long customerId);

    boolean existsByStatusAndVisitIdIn(OrderStatus status, Collection<Long> visitIds);

    boolean existsByStatusAndBoardingBookingIdIn(OrderStatus status, Collection<Long> bookingIds);

    /** Order {@code OPEN} / {@code PENDING} của khách (index {@code (customer_id, status)}), theo id tăng dần. */
    @Query("SELECT o.id AS id, o.branchId AS branchId FROM SalesOrder o "
            + "WHERE o.customerId = :customerId AND o.status IN :statuses ORDER BY o.id")
    List<UnpaidRow> findIdAndBranchByCustomer(@Param("customerId") Long customerId,
            @Param("statuses") Collection<OrderStatus> statuses);

    interface UnpaidRow {
        Long getId();

        Long getBranchId();
    }

    /**
     * Order nguồn VISIT của các lượt (đọc không khóa, projection; index {@code uq_orders_visit_id}). Thêm 2026-10-20 bởi
     * BE-1 cho hàng đợi visit (docs/adr/0060). Bên gọi không truyền tập rỗng.
     */
    @Query("SELECT o.visitId AS visitId, o.id AS id FROM SalesOrder o WHERE o.visitId IN :visitIds")
    List<VisitOrderRow> findVisitOrderIds(@Param("visitIds") Collection<Long> visitIds);

    /** I-8: lần mua gần nhất (thời điểm {@code paid_at}) của từng sản phẩm trong Order {@code PAID} của khách. */
    @Query("""
            SELECT l.productId AS productId, MAX(o.paidAt) AS lastPaidAt
            FROM SalesOrder o JOIN o.lines l
            WHERE o.customerId = :customerId AND o.status = com.petcare.module.sales.entity.OrderStatus.PAID
              AND l.productId IN :productIds
            GROUP BY l.productId
            """)
    List<PurchaseRow> findLastPurchases(@Param("customerId") Long customerId,
            @Param("productIds") Collection<Long> productIds);

    /** I-8: tổng số lượng từng sản phẩm bán ra trong Order {@code PAID} có {@code paid_at >= from}, toàn chuỗi. */
    @Query("""
            SELECT l.productId AS productId, SUM(l.quantity) AS units
            FROM SalesOrder o JOIN o.lines l
            WHERE o.status = com.petcare.module.sales.entity.OrderStatus.PAID AND o.paidAt >= :from
              AND l.productId IS NOT NULL
            GROUP BY l.productId
            """)
    List<SoldRow> sumSoldUnits(@Param("from") java.time.Instant from);

    /** Dòng {@code SERVICE} của Order nguồn VISIT, theo lô lượt; lượt không có Order hoặc dòng dịch vụ thì vắng mặt. */
    @Query("""
            SELECT o.visitId AS visitId, l.serviceId AS serviceId
            FROM SalesOrder o JOIN o.lines l
            WHERE o.visitId IN :visitIds AND l.lineType = com.petcare.module.sales.entity.OrderLineType.SERVICE
            ORDER BY o.visitId, l.id
            """)
    List<VisitServiceRow> findVisitServiceIds(@Param("visitIds") Collection<Long> visitIds);

    interface PurchaseRow {
        Long getProductId();

        java.time.Instant getLastPaidAt();
    }

    interface SoldRow {
        Long getProductId();

        Long getUnits();
    }

    interface VisitServiceRow {
        Long getVisitId();

        Long getServiceId();
    }

    interface VisitOrderRow {
        Long getVisitId();

        Long getId();
    }

    /**
     * Order lưu trú chưa kết thúc ({@code OPEN} / {@code PENDING}) của đặt chỗ, khóa {@code FOR NO KEY UPDATE}
     * (docs/adr/0086). Tối đa 1 dòng nhờ {@code uq_orders_boarding_unfinished}. Khi một thu tiền commit trong lúc chờ
     * khóa, Postgres đánh giá lại điều kiện trạng thái và bỏ dòng → bên gọi phải kiểm {@code PAID} sau khóa.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.boardingBookingId = :bookingId "
            + "AND o.status IN (com.petcare.module.sales.entity.OrderStatus.OPEN, "
            + "com.petcare.module.sales.entity.OrderStatus.PENDING)")
    Optional<SalesOrder> findUnfinishedByBookingForUpdate(@Param("bookingId") Long bookingId);

    boolean existsByBoardingBookingIdAndStatus(Long bookingId, OrderStatus status);

    Optional<SalesOrder> findFirstByBoardingBookingIdAndStatusOrderByIdDesc(Long bookingId, OrderStatus status);

    /** Order lưu trú chưa kết thúc của các đặt chỗ (đọc không khóa, index {@code uq_orders_boarding_unfinished}). */
    @Query("SELECT o.boardingBookingId AS bookingId, o.id AS orderId FROM SalesOrder o WHERE o.boardingBookingId IN "
            + ":bookingIds AND o.status IN (com.petcare.module.sales.entity.OrderStatus.OPEN, "
            + "com.petcare.module.sales.entity.OrderStatus.PENDING)")
    List<BookingOrderRow> findUnfinishedByBookingIds(@Param("bookingIds") Collection<Long> bookingIds);

    interface BookingOrderRow {
        Long getBookingId();

        Long getOrderId();
    }
}
