package com.petcare.module.visit.entity;

import java.time.Instant;

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
 * Bảng {@code visits} (erd §6, ROOT, SM #4). Một lượt tiếp nhận: 1 thú tại 1 chi nhánh, tối đa 1 lịch hẹn
 * ({@code appointmentId} null = walk-in), mở đúng 1 Order (BR-TN-01). {@code branchId}, {@code customerId},
 * {@code petId}, {@code appointmentId}, {@code assigneeId}, {@code checkedInBy} để dạng id, không map quan hệ sang bảng
 * của module khác (convention 01, 02 <i>Entity</i>).
 * <p>
 * {@code customerId} là chủ tại thời điểm tiếp nhận. {@code serviceGroup} ({@code MEDICAL} / {@code GROOMING}) quyết
 * định chức vụ được gán (BR-TN-05). {@code queueSortKey} là giờ hẹn hoặc giờ check-in; lễ tân sắp lại thì sửa cột này
 * (BR-TN-04). CHECK {@code ck_visits_assignee}: lượt đã gọi luôn có người phụ trách.
 * <p>
 * Command (docs/adr/0058): {@code checkInVisit} (#1), {@code assignVisit} (#2), {@code callVisit} (#3),
 * {@code reassignCalledVisit} (#4), {@code completeVisit} (#5), {@code cancelVisit} (#6); ngoài bảng 03:
 * {@code moveVisitInQueue}, {@code markVisitEmergency}. Method đổi trạng thái thêm cùng phần của T26 dựng thao tác đó.
 */
@Getter
@Entity
@Table(name = "visits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Visit extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Column(name = "appointment_id", updatable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_group", nullable = false)
    private ServiceGroup serviceGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VisitStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority_class", nullable = false)
    private PriorityClass priorityClass;

    @Column(name = "queue_sort_key", nullable = false)
    private Instant queueSortKey;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "needs_reassign", nullable = false)
    private boolean needsReassign;

    @Column(name = "checked_in_by", nullable = false, updatable = false)
    private Long checkedInBy;

    @Column(name = "checked_in_at", nullable = false, updatable = false)
    private Instant checkedInAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;
}
