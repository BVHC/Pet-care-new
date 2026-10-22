package com.petcare.module.inventory.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
 * Bảng {@code stock_receipts} (erd §9, ROOT, SM #9). Phiếu không bị xóa cứng để dãy số liên tục (BR-KO-03). Chuyển
 * trạng thái do service kiểm bằng {@code StockReceiptTransitionHandler} trước khi gọi các method ở đây; caller giữ
 * khóa dòng phiếu ({@code FOR NO KEY UPDATE}) từ trước khi đọc entity.
 */
@Getter
@Entity
@Table(name = "stock_receipts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReceipt extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StockReceiptStatus status;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "stock_receipt_id", nullable = false)
    @OrderBy("id")
    private List<StockReceiptLine> lines = new ArrayList<>();

    public static StockReceipt draft(String code, Long branchId, Long supplierId, LocalDate receiptDate, String note,
            Long createdBy, List<StockReceiptLine> lines) {
        StockReceipt receipt = new StockReceipt();
        receipt.code = code;
        receipt.branchId = branchId;
        receipt.supplierId = supplierId;
        receipt.receiptDate = receiptDate;
        receipt.note = note;
        receipt.createdBy = createdBy;
        receipt.status = StockReceiptStatus.DRAFT;
        receipt.lines.addAll(lines);
        return receipt;
    }

    /** PUT thay toàn bộ phiếu nháp (contract A2): giữ mã và người tạo. */
    public void replaceDraft(Long newSupplierId, LocalDate newReceiptDate, String newNote,
            List<StockReceiptLine> newLines) {
        this.supplierId = newSupplierId;
        this.receiptDate = newReceiptDate;
        this.note = newNote;
        this.lines.clear();
        this.lines.addAll(newLines);
    }

    public void confirm(Long actorId, Instant now) {
        this.status = StockReceiptStatus.CONFIRMED;
        this.confirmedBy = actorId;
        this.confirmedAt = now;
    }

    public void cancel(Long actorId, Instant now, String reason) {
        this.status = StockReceiptStatus.CANCELLED;
        this.cancelledBy = actorId;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }
}
