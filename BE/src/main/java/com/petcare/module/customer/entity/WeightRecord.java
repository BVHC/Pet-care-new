package com.petcare.module.customer.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.petcare.module.customer.api.WeightSource;
import com.petcare.platform.model.CreatedAtEntity;

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
 * Bảng {@code weight_records} (erd §3, LOG, chỉ thêm mới). Cân nặng hiện tại của thú là bản ghi có {@code measured_at}
 * mới nhất (BR-KH-04). {@code visitId} / {@code boardingBookingId} / {@code recordedBy} để dạng id (bảng của module khác).
 */
@Getter
@Entity
@Table(name = "weight_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WeightRecord extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Column(name = "weight_kg", nullable = false, updatable = false)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private WeightSource source;

    @Column(name = "visit_id", updatable = false)
    private Long visitId;

    @Column(name = "boarding_booking_id", updatable = false)
    private Long boardingBookingId;

    @Column(name = "recorded_by", updatable = false)
    private Long recordedBy;

    @Column(name = "measured_at", nullable = false, updatable = false)
    private Instant measuredAt;

    public static WeightRecord create(Long petId, BigDecimal weightKg, WeightSource source, Long visitId,
            Long boardingBookingId, Long recordedBy, Instant measuredAt) {
        WeightRecord record = new WeightRecord();
        record.petId = petId;
        record.weightKg = weightKg;
        record.source = source;
        record.visitId = visitId;
        record.boardingBookingId = boardingBookingId;
        record.recordedBy = recordedBy;
        record.measuredAt = measuredAt;
        return record;
    }
}
