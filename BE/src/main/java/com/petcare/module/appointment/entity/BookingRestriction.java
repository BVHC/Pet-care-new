package com.petcare.module.appointment.entity;

import java.time.Instant;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code booking_restrictions} (erd §5, ROOT): một lần khách bị hạn chế đặt online (BR-LH-09). Khách đang bị hạn
 * chế ⇔ có dòng {@code starts_at ≤ now < ends_at AND lifted_at IS NULL} (erd §5). {@code violation_count} là
 * {@code SMALLINT} nên field là {@code short}; {@code customerId}, {@code liftedBy} để dạng id (convention 02).
 * <p>
 * T16 phần 2 (docs/adr/0043) chỉ đọc; tạo (phần 4 / ST05) và gỡ sớm (#9) thêm method khi cần.
 */
@Getter
@Entity
@Table(name = "booking_restrictions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingRestriction extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Column(name = "violation_count", nullable = false, updatable = false)
    private short violationCount;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lifted_by")
    private Long liftedBy;

    @Column(name = "lift_reason")
    private String liftReason;
}
