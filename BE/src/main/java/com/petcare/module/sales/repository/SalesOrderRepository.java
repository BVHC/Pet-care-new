package com.petcare.module.sales.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.visitId = :visitId")
    Optional<SalesOrder> findByVisitIdForUpdate(@Param("visitId") Long visitId);

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

    interface VisitOrderRow {
        Long getVisitId();

        Long getId();
    }
}
