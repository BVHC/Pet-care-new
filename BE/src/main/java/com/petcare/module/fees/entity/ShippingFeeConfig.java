package com.petcare.module.fees.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code shipping_fee_configs} (REF, V43): đúng một dòng {@code id = 1} do V43 gieo (0, 0); chỉ UPDATE, không
 * bao giờ INSERT (BR-SP-08, docs/adr/0140).
 */
@Getter
@Entity
@Table(name = "shipping_fee_configs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingFeeConfig extends TimestampedEntity {

    public static final long SINGLE_ROW_ID = 1L;

    @Id
    private Long id;

    @Column(name = "base_fee", nullable = false)
    private long baseFee;

    @Column(name = "free_threshold", nullable = false)
    private long freeThreshold;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** Thay cả hai giá trị (PUT ghi đè toàn bộ); {@code updated_at} do listener đặt từ {@code Clock}. */
    public void replace(long baseFee, long freeThreshold, Long updatedBy) {
        this.baseFee = baseFee;
        this.freeThreshold = freeThreshold;
        this.updatedBy = updatedBy;
    }
}
