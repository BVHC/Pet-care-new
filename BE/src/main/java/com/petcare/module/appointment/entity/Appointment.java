package com.petcare.module.appointment.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.petcare.module.catalog.api.ServiceGroup;
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
 * Bảng {@code appointments} (erd §5, ROOT, SM #3). Một lịch hẹn: 1 thú, 1 dịch vụ, 1 chi nhánh, 1 khung 30 phút, không
 * gắn nhân viên (BR-LH-01). {@code customerId}, {@code petId}, {@code branchId}, {@code serviceId}, {@code bookedBy} để
 * dạng id, không map quan hệ sang bảng của module khác (convention 01, 02 <i>Entity</i>).
 * <p>
 * {@code serviceGroup} là bản sao nhóm dịch vụ lúc đặt để đếm quota (erd L476), không đọc lại từ catalog; CHECK chỉ cho
 * {@code MEDICAL}, {@code GROOMING}. {@code code} giữ nguyên khi đổi khung (BR-LH-06). Không có cột {@code visit_id}: quan
 * hệ với Visit nằm ở {@code visits.appointment_id} (module visit). {@code reschedule_count} là {@code SMALLINT} nên field
 * là {@code short}.
 * <p>
 * T16 phần 0 (docs/adr/0041) chỉ khai cột; method tạo / đổi khung / hủy thêm ở phần dùng tới chúng.
 */
@Getter
@Entity
@Table(name = "appointments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appointment extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private Long serviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_group", nullable = false, updatable = false)
    private ServiceGroup serviceGroup;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppointmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private AppointmentChannel channel;

    @Column(name = "booked_by", nullable = false, updatable = false)
    private Long bookedBy;

    @Column(name = "reschedule_count", nullable = false)
    private short rescheduleCount;

    @Column(name = "late_cancel", nullable = false)
    private boolean lateCancel;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_source")
    private AppointmentCancelSource cancelSource;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "note", updatable = false)
    private String note;
}
