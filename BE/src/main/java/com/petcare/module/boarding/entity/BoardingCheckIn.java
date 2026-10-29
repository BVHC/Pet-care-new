package com.petcare.module.boarding.entity;

import java.time.Instant;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Bảng {@code boarding_check_ins} (erd §7, PART 1–1 của đặt chỗ): tình trạng ghi lúc nhận thú (BR-LT-08). */
@Getter
@Entity
@Table(name = "boarding_check_ins")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardingCheckIn extends TimestampedEntity {

    @Id
    @Column(name = "boarding_booking_id")
    private Long bookingId;

    @Column(name = "weight_record_id", nullable = false, updatable = false)
    private Long weightRecordId;

    @Column(name = "health_condition", nullable = false)
    private String healthCondition;

    @Column(name = "belongings")
    private String belongings;

    @Column(name = "diet_instructions")
    private String dietInstructions;

    @Column(name = "emergency_phone", nullable = false)
    private String emergencyPhone;

    @Column(name = "received_by", nullable = false, updatable = false)
    private Long receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    public static BoardingCheckIn record(Long bookingId, Long weightRecordId, String healthCondition,
            String belongings, String dietInstructions, String emergencyPhone, Long receivedBy, Instant receivedAt) {
        BoardingCheckIn row = new BoardingCheckIn();
        row.bookingId = bookingId;
        row.weightRecordId = weightRecordId;
        row.healthCondition = healthCondition;
        row.belongings = belongings;
        row.dietInstructions = dietInstructions;
        row.emergencyPhone = emergencyPhone;
        row.receivedBy = receivedBy;
        row.receivedAt = receivedAt;
        return row;
    }
}
