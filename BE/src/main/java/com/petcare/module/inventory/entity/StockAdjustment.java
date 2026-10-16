package com.petcare.module.inventory.entity;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

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
 * Bảng {@code stock_adjustments} (erd §9, ROOT, không có trạng thái): lưu là có hiệu lực ngay (BR-KO-06). Bất biến sau
 * khi lưu — không có method sửa. {@code @BatchSize} để danh sách nạp dòng của cả trang bằng một câu.
 */
@Getter
@Entity
@Table(name = "stock_adjustments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAdjustment extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private StockAdjustmentReason reason;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "stock_adjustment_id", nullable = false, updatable = false)
    @OrderBy("id")
    @BatchSize(size = 100)
    private List<StockAdjustmentLine> lines = new ArrayList<>();

    public static StockAdjustment create(String code, Long branchId, StockAdjustmentReason reason, String note,
            Long createdBy, List<StockAdjustmentLine> lines) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.code = code;
        adjustment.branchId = branchId;
        adjustment.reason = reason;
        adjustment.note = note;
        adjustment.createdBy = createdBy;
        adjustment.lines.addAll(lines);
        return adjustment;
    }
}
