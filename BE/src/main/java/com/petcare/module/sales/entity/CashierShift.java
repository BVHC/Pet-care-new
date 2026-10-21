package com.petcare.module.sales.entity;

import java.time.Instant;
import java.time.LocalDate;

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
 * Bảng {@code cashier_shifts} (erd §8, ROOT, SM #8). Service kiểm {@code CashierShiftTransitionHandler} và giữ khóa dòng
 * ca trước khi gọi các method chuyển trạng thái ở đây (docs/adr/0064). {@code isAfterHours} đặt lúc mở ca và không đổi.
 */
@Getter
@Entity
@Table(name = "cashier_shifts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashierShift extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "cashier_id", nullable = false, updatable = false)
    private Long cashierId;

    @Column(name = "is_after_hours", nullable = false, updatable = false)
    private boolean afterHours;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CashierShiftStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "auto_closed", nullable = false)
    private boolean autoClosed;

    @Column(name = "expected_cash")
    private Long expectedCash;

    @Column(name = "expected_transfer")
    private Long expectedTransfer;

    @Column(name = "counted_cash")
    private Long countedCash;

    @Column(name = "difference")
    private Long difference;

    @Column(name = "reconcile_note")
    private String reconcileNote;

    @Column(name = "reconciled_by")
    private Long reconciledBy;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    /** Ca thu ngân#1 (ca thường) / #2 (ca ngoài giờ): — → OPEN. */
    public static CashierShift open(Long branchId, Long cashierId, boolean afterHours, LocalDate businessDate,
            Instant openedAt) {
        CashierShift shift = new CashierShift();
        shift.branchId = branchId;
        shift.cashierId = cashierId;
        shift.afterHours = afterHours;
        shift.businessDate = businessDate;
        shift.openedAt = openedAt;
        shift.status = CashierShiftStatus.OPEN;
        return shift;
    }

    /** Ca thu ngân#3: lễ tân chốt kèm số tiền mặt thực đếm. */
    public void closeByCashier(long countedCash, ShiftTotals totals, Instant now) {
        close(totals, now);
        this.countedCash = countedCash;
        this.autoClosed = false;
    }

    /** Ca thu ngân#4, #5: ST13 tự chốt, chưa có số thực đếm. */
    public void autoClose(ShiftTotals totals, Instant now) {
        close(totals, now);
        this.autoClosed = true;
    }

    /**
     * Ca thu ngân#6: {@code countedCashOrNull} khác null thì ghi đè số thực đếm; chênh lệch = thực đếm − tiền mặt hệ
     * thống ghi nhận. Caller đã kiểm ca có số thực đếm khi {@code countedCashOrNull} null (BR-TG-05).
     */
    public void reconcile(Long countedCashOrNull, String note, Long managerId, Instant now) {
        if (countedCashOrNull != null) {
            this.countedCash = countedCashOrNull;
        }
        this.difference = Math.subtractExact(this.countedCash, this.expectedCash);
        this.reconcileNote = note;
        this.reconciledBy = managerId;
        this.reconciledAt = now;
        this.status = CashierShiftStatus.RECONCILED;
    }

    private void close(ShiftTotals totals, Instant now) {
        this.expectedCash = totals.cash();
        this.expectedTransfer = totals.transfer();
        this.closedAt = now;
        this.status = CashierShiftStatus.CLOSED;
    }
}
