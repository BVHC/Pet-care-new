package com.petcare.module.sales.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;

import jakarta.persistence.LockModeType;

/** Ca thu ngân (docs/adr/0064). Mọi thao tác ghi khóa dòng ca trước khi đọc entity. */
public interface CashierShiftRepository extends JpaRepository<CashierShift, Long> {

    /** Ca {@code OPEN} của một lễ tân: id và chi nhánh. */
    interface OpenShiftRow {
        Long getId();

        Long getBranchId();
    }

    /** Khóa phân quyền của một ca: bất biến nên đọc không khóa trước khi khóa dòng (tránh entity cũ). */
    interface ShiftAccessRow {
        Long getId();

        Long getBranchId();

        Long getCashierId();
    }

    /** Các cột bất biến đủ để tính hạn tự chốt của ca (ST13, docs/adr/0103): đọc không khóa, không nạp entity. */
    interface ShiftDeadlineRow {
        Long getId();

        Long getBranchId();

        Boolean getAfterHours();

        LocalDate getBusinessDate();

        Instant getOpenedAt();
    }

    /**
     * ST13: hạn tự chốt chỉ phụ thuộc cột bất biến nên tính <b>trước</b> khi khóa; chỉ ca đã đến hạn mới bị khóa, phiếu
     * thu của ca chưa đến hạn không phải chờ job. Rỗng khi ca không còn {@code OPEN}.
     */
    @Query("SELECT s.id AS id, s.branchId AS branchId, s.afterHours AS afterHours, s.businessDate AS businessDate, "
            + "s.openedAt AS openedAt FROM CashierShift s WHERE s.id = :id "
            + "AND s.status = com.petcare.module.sales.entity.CashierShiftStatus.OPEN")
    Optional<ShiftDeadlineRow> findOpenDeadlineRow(@Param("id") Long id);

    /** Đọc thuần, không khóa; dùng {@code uq_cashier_shifts_one_open}. */
    @Query(value = "SELECT id AS id, branch_id AS branchId FROM cashier_shifts "
            + "WHERE cashier_id = :cashierId AND status = 'OPEN'", nativeQuery = true)
    Optional<OpenShiftRow> findOpenShiftRow(@Param("cashierId") Long cashierId);

    /** Ca {@code OPEN} của một lễ tân dưới dạng entity, đọc không khóa (sales-v1 #17). */
    Optional<CashierShift> findFirstByCashierIdAndStatus(Long cashierId, CashierShiftStatus status);

    @Query(value = "SELECT id AS id, branch_id AS branchId, cashier_id AS cashierId FROM cashier_shifts "
            + "WHERE id = :id", nativeQuery = true)
    Optional<ShiftAccessRow> findAccessRowById(@Param("id") Long id);

    /**
     * Thu tiền (docs/adr/0064): khóa chia sẻ ca {@code OPEN} của lễ tân tới hết transaction. Chốt ca / ST13 lấy
     * {@code FOR NO KEY UPDATE} (xung đột với {@code FOR SHARE}) nên chờ phiếu thu đang chạy, và phiếu thu đến sau khi
     * ca đã chốt không còn thấy ca {@code OPEN} (điều kiện được đánh giá lại sau khi chờ khóa, READ COMMITTED).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id AS id, branch_id AS branchId FROM cashier_shifts "
            + "WHERE cashier_id = :cashierId AND status = 'OPEN' FOR SHARE", nativeQuery = true)
    Optional<OpenShiftRow> lockOpenShiftForShare(@Param("cashierId") Long cashierId);

    /** ST13: id các ca {@code OPEN} sau {@code lastId}, tăng dần (keyset); đọc thuần, dùng {@code ix_cashier_shifts_open}. */
    @Query(value = "SELECT id FROM cashier_shifts WHERE status = 'OPEN' AND id > :lastId ORDER BY id LIMIT :limit",
            nativeQuery = true)
    List<Long> findOpenIdsAfter(@Param("lastId") long lastId, @Param("limit") int limit);

    /**
     * ST13: khóa {@code FOR NO KEY UPDATE SKIP LOCKED} một ca còn {@code OPEN}. Rỗng khi ca đang bị khóa (phiếu thu
     * giữ {@code FOR SHARE}, lượt chốt khác) hoặc đã không còn {@code OPEN}: job bỏ qua, lượt sau thử lại.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT * FROM cashier_shifts WHERE id = :id AND status = 'OPEN' FOR NO KEY UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<CashierShift> lockOpenSkipLocked(@Param("id") Long id);

    /** {@code FOR NO KEY UPDATE}; gọi trước khi đọc entity ca, trong transaction của use case. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CashierShift s WHERE s.id = :id")
    Optional<CashierShift> findByIdForUpdate(@Param("id") Long id);
}
