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
 * Tạo bằng {@link #start} (vi phạm thứ N, docs/adr/0049); gỡ sớm bằng {@link #lift} (appointment-v1 #9, docs/adr/0055).
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

    /**
     * Một lần hạn chế mới (BR-LH-09, docs/adr/0049): bắt đầu đúng lúc xảy ra vi phạm làm đủ ngưỡng, kéo dài
     * {@code booking_restriction.duration_days} [CFG]. {@code violationCount} chỉ để hiển thị.
     */
    public static BookingRestriction start(Long customerId, Instant startsAt, Instant endsAt, int violationCount) {
        BookingRestriction restriction = new BookingRestriction();
        restriction.customerId = customerId;
        restriction.startsAt = startsAt;
        restriction.endsAt = endsAt;
        restriction.violationCount = (short) Math.min(violationCount, Short.MAX_VALUE);
        return restriction;
    }

    /**
     * Gỡ sớm (BR-LH-09, docs/adr/0055): hết hiệu lực vì {@code lifted_at IS NOT NULL}, {@code ends_at} giữ nguyên. Service
     * chỉ gọi với dòng đang hiệu lực đọc dưới khóa 1002; gỡ lần hai là lỗi lập trình.
     */
    public void lift(Instant at, Long liftedBy, String reason) {
        if (liftedAt != null) {
            throw new IllegalStateException("Hạn chế #" + id + " đã được gỡ lúc " + liftedAt);
        }
        this.liftedAt = at;
        this.liftedBy = liftedBy;
        this.liftReason = reason;
    }
}
