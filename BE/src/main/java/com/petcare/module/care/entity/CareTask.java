package com.petcare.module.care.entity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
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
 * Bảng {@code care_tasks} (erd §11, ROOT, 03 §10): việc gọi điện cho khách do ST18 sinh (BR-TB-05). Entity chỉ giữ id của
 * dòng nguồn, không có quan hệ JPA. Nhóm cột định danh ({@code branch_id}, {@code customer_id}, {@code pet_id},
 * {@code task_type}, ba cột nguồn, {@code due_date}) là {@code updatable = false}: Hibernate UPDATE mọi cột còn lại, nên
 * một lần lưu entity không được ghi đè {@code customer_id} mà luồng chuyển chủ đổi bằng UPDATE riêng (06 §8 Q1,
 * docs/adr/0079). Sáu cột xử lý ({@code status}, {@code result}, {@code note}, {@code cancel_reason}, {@code handled_by},
 * {@code handled_at}) do lễ tân ghi qua {@link #complete} / {@link #cancel} (care-v1 #3 / #4, docs/adr/0080); ngoài ra
 * chỉ có một đường tạo ({@link #open}) và các câu UPDATE hủy do hệ thống ở {@code CareTaskRepository}. Hai method ghi
 * <b>không</b> tự kiểm trạng thái: bên gọi đã khóa dòng và qua {@code CareTaskTransitionHandler}. Bất biến giữa các cột
 * theo trạng thái được giữ hai lớp: hai method này ghi đủ cột, và DB có ba CHECK từ V22 (docs/adr/0081, trả D049).
 */
@Getter
@Entity
@Table(name = "care_tasks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareTask extends TimestampedEntity {

    /** Độ dài tối đa của lý do hủy ({@code cancel_reason VARCHAR(300)}, erd §11). */
    public static final int CANCEL_REASON_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, updatable = false, length = 30)
    private CareTaskType taskType;

    @Column(name = "vaccination_id", updatable = false)
    private Long vaccinationId;

    @Column(name = "medical_record_id", updatable = false)
    private Long medicalRecordId;

    @Column(name = "boarding_booking_id", updatable = false)
    private Long boardingBookingId;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CareTaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 30)
    private CareTaskResult result;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "cancel_reason", length = CANCEL_REASON_MAX_LENGTH)
    private String cancelReason;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    /**
     * Care Task#1: task mới ở {@code OPEN}, năm cột xử lý NULL. {@code sourceId} vào đúng một cột theo loại (CHECK
     * {@code ck_care_tasks_target}): {@code VACCINE_*} → mũi tiêm, {@code FOLLOW_UP_DUE} → bệnh án (khóa là id Visit),
     * {@code PICKUP_OVERDUE} → đặt chỗ. Mọi trường của {@code task} bắt buộc.
     */
    public static CareTask open(NewCareTask task) {
        requireNonNull(task, "task");
        CareTask entity = new CareTask();
        entity.taskType = requireNonNull(task.type(), "type");
        Long sourceId = requireNonNull(task.sourceId(), "sourceId");
        switch (entity.taskType) {
            case VACCINE_DUE, VACCINE_OVERDUE -> entity.vaccinationId = sourceId;
            case FOLLOW_UP_DUE -> entity.medicalRecordId = sourceId;
            case PICKUP_OVERDUE -> entity.boardingBookingId = sourceId;
        }
        entity.branchId = requireNonNull(task.branchId(), "branchId");
        entity.customerId = requireNonNull(task.customerId(), "customerId");
        entity.petId = requireNonNull(task.petId(), "petId");
        entity.dueDate = requireNonNull(task.dueDate(), "dueDate");
        entity.status = CareTaskStatus.OPEN;
        return entity;
    }

    /**
     * Care Task#2 {@code OPEN → DONE} (BR-TB-05): kết quả và ghi chú bắt buộc, người xử lý là lễ tân. {@code cancel_reason}
     * giữ NULL (CHECK {@code ck_care_tasks_done_result} chỉ buộc {@code result}). Bên gọi đã khóa dòng và kiểm FSM.
     */
    public void complete(CareTaskResult result, String note, Long handledBy, Instant handledAt) {
        this.status = CareTaskStatus.DONE;
        this.result = requireNonNull(result, "result");
        this.note = requireNonNull(note, "note");
        this.handledBy = requireNonNull(handledBy, "handledBy");
        this.handledAt = requireNonNull(handledAt, "handledAt");
    }

    /**
     * Care Task#3 {@code OPEN → CANCELLED} (BR-TB-05): lý do bắt buộc, người xử lý là lễ tân (khác hủy do hệ thống #4–#6
     * để {@code handled_by} NULL). {@code result}, {@code note} giữ NULL. Bên gọi đã khóa dòng và kiểm FSM.
     */
    public void cancel(String reason, Long handledBy, Instant handledAt) {
        this.status = CareTaskStatus.CANCELLED;
        this.cancelReason = requireNonNull(reason, "reason");
        this.handledBy = requireNonNull(handledBy, "handledBy");
        this.handledAt = requireNonNull(handledAt, "handledAt");
    }
}
