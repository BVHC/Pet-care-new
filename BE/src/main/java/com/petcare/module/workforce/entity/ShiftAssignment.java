package com.petcare.module.workforce.entity;

import static java.util.Objects.requireNonNull;

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
 * Bảng {@code shift_assignments} (erd §14.1, ROOT, 03 #12): một nhân viên A06–A08 × một ca × một ngày. Các cột định
 * danh {@code branch_id}, {@code staff_id}, {@code work_date}, {@code source} là {@code updatable = false}:
 * {@code source} quyết định phân ca nào nhân viên được tự hủy khi sửa đăng ký tuần (BR-NS-11), nên không được đổi;
 * {@code shift_definition_id} <b>cập nhật được</b> vì Phân ca#5 (đổi ca) chỉ đổi cột này.
 * <p>
 * Mỗi method chuyển trạng thái ghi đủ cột theo hai CHECK của V36: {@code ck_shift_assignments_status_changed}
 * ({@code status_changed_at} NULL ⇔ còn {@code SCHEDULED}) và {@code ck_shift_assignments_reason} ({@code ABSENT},
 * {@code CANCELLED} bắt buộc có {@code reason}). Phân ca#5 vì vậy <b>không</b> đặt {@code status_changed_*}: dấu vết
 * của việc đổi ca nằm ở {@code shift_requests} ({@code decided_by}, {@code closed_at}).
 * <p>
 * Cả bốn cạnh ra khỏi {@code SCHEDULED} nên mỗi method gọi {@code requireScheduled()}: lớp chặn cuối nếu bên gọi quên
 * {@code ShiftAssignmentTransitionHandler}, <b>không</b> thay handler (nguồn duy nhất của bảng cạnh, chạy trước để ra
 * 409). Guard nghiệp vụ cần Clock / [CFG] / dòng khác (ca chưa bắt đầu, hạn ghi vắng, chồng giờ, phạm vi chi nhánh)
 * do service ở T59 làm (docs/adr/0149).
 */
@Getter
@Entity
@Table(name = "shift_assignments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftAssignment extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "staff_id", nullable = false, updatable = false)
    private Long staffId;

    @Column(name = "shift_definition_id", nullable = false)
    private Long shiftDefinitionId;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ShiftAssignmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 30)
    private ShiftAssignmentSource source;

    @Column(name = "reason", length = EntityText.NOTE_MAX_LENGTH)
    private String reason;

    @Column(name = "status_changed_by")
    private Long statusChangedBy;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** Phân ca#1: {@code — → SCHEDULED}. Mọi tham số bắt buộc; {@code source} không có giá trị mặc định (V61). */
    public static ShiftAssignment schedule(Long branchId, Long staffId, Long shiftDefinitionId, LocalDate workDate,
            ShiftAssignmentSource source) {
        ShiftAssignment entity = new ShiftAssignment();
        entity.branchId = requireNonNull(branchId, "branchId");
        entity.staffId = requireNonNull(staffId, "staffId");
        entity.shiftDefinitionId = requireNonNull(shiftDefinitionId, "shiftDefinitionId");
        entity.workDate = requireNonNull(workDate, "workDate");
        entity.source = requireNonNull(source, "source");
        entity.status = ShiftAssignmentStatus.SCHEDULED;
        return entity;
    }

    /**
     * Phân ca#2 (BRANCH_MANAGER hủy), #6 (nhân viên rời chi nhánh, {@code by} = null) và #7 (nhân viên sửa đăng ký
     * tuần, {@code by} = nhân viên): {@code SCHEDULED → CANCELLED}. Lý do bắt buộc; {@code by} null khi hệ thống chuyển.
     */
    public void cancel(String reason, Long by, Instant at) {
        requireScheduled();
        String text = EntityText.require(reason, EntityText.NOTE_MAX_LENGTH, "reason");
        Instant changedAt = requireNonNull(at, "at");
        this.status = ShiftAssignmentStatus.CANCELLED;
        this.reason = text;
        this.statusChangedBy = by;
        this.statusChangedAt = changedAt;
    }

    /**
     * Phân ca#3: {@code SCHEDULED → ON_LEAVE} khi BRANCH_MANAGER duyệt yêu cầu {@code LEAVE} (BR-NS-06). {@code by} là
     * người duyệt, {@code reason} chép từ lý do của yêu cầu (ADR-0149).
     */
    public void markOnLeave(String reason, Long by, Instant at) {
        requireScheduled();
        String text = EntityText.require(reason, EntityText.NOTE_MAX_LENGTH, "reason");
        Long changedBy = requireNonNull(by, "by");
        Instant changedAt = requireNonNull(at, "at");
        this.status = ShiftAssignmentStatus.ON_LEAVE;
        this.reason = text;
        this.statusChangedBy = changedBy;
        this.statusChangedAt = changedAt;
    }

    /** Phân ca#4: {@code SCHEDULED → ABSENT} (BR-NS-07). Lý do bắt buộc, {@code by} là BRANCH_MANAGER ghi nhận. */
    public void markAbsent(String reason, Long by, Instant at) {
        requireScheduled();
        String text = EntityText.require(reason, EntityText.NOTE_MAX_LENGTH, "reason");
        Long changedBy = requireNonNull(by, "by");
        Instant changedAt = requireNonNull(at, "at");
        this.status = ShiftAssignmentStatus.ABSENT;
        this.reason = text;
        this.statusChangedBy = changedBy;
        this.statusChangedAt = changedAt;
    }

    /**
     * Phân ca#5: {@code SCHEDULED → SCHEDULED} khi duyệt yêu cầu {@code SWAP} (BR-NS-06): chỉ đổi
     * {@code shift_definition_id}. Không đặt {@code status_changed_*} (CHECK buộc NULL khi còn {@code SCHEDULED}).
     * Ca đích đang dùng và không chồng giờ do service kiểm.
     */
    public void swapTo(Long targetShiftDefinitionId) {
        requireScheduled();
        Long target = requireNonNull(targetShiftDefinitionId, "targetShiftDefinitionId");
        this.shiftDefinitionId = target;
    }

    private void requireScheduled() {
        if (status != ShiftAssignmentStatus.SCHEDULED) {
            throw new IllegalStateException("Phân ca đã ở trạng thái cuối " + status);
        }
    }
}
