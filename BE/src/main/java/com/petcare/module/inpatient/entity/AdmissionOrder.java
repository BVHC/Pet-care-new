package com.petcare.module.inpatient.entity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

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
 * Bảng {@code admission_orders} (V38, ROOT, 09 §C FSM #25): y lệnh nội trú, <b>một y lệnh là một lần thực hiện</b>
 * (BR-LT-15). Cột nội dung y lệnh là {@code updatable = false}; chỉ nhóm {@code status}, {@code done_*},
 * {@code cancel_reason} đổi khi xử lý ({@code ck_admission_orders_done_fields}, {@code _cancelled_reason}).
 * <p>
 * Hủy y lệnh chưa lưu người hủy và lúc hủy (V38 không có cột; docs/adr/0149, nợ D078).
 */
@Getter
@Entity
@Table(name = "admission_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionOrder extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admission_id", nullable = false, updatable = false)
    private Long admissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, updatable = false, length = 30)
    private AdmissionOrderType orderType;

    @Column(name = "content", nullable = false, updatable = false, length = InpatientText.CONTENT_MAX_LENGTH)
    private String content;

    @Column(name = "scheduled_at", nullable = false, updatable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AdmissionOrderStatus status;

    /** Bắt buộc với {@code MEDICATION}, không unique: nhiều y lệnh cùng một dòng đơn thuốc (BR-LT-15). */
    @Column(name = "prescription_item_id", updatable = false)
    private Long prescriptionItemId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "done_by")
    private Long doneBy;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_note", length = InpatientText.NOTE_MAX_LENGTH)
    private String doneNote;

    @Column(name = "cancel_reason", length = InpatientText.NOTE_MAX_LENGTH)
    private String cancelReason;

    /**
     * Y lệnh#1: {@code — → ORDERED}. Chỉ ra được cho ca còn {@code ADMITTED} (lớp chặn cuối cho 409 của
     * inpatient-v1 #5); {@code admission} phải đã lưu. {@code MEDICATION} bắt buộc có dòng đơn thuốc; việc dòng đơn
     * thuộc Visit của ca do service kiểm (module visit).
     */
    public static AdmissionOrder order(Admission admission, AdmissionOrderType type, String content,
            Instant scheduledAt, Long prescriptionItemId, Long createdBy) {
        requireNonNull(admission, "admission").requireAdmitted();
        AdmissionOrderType orderType = requireNonNull(type, "type");
        if (orderType == AdmissionOrderType.MEDICATION && prescriptionItemId == null) {
            throw new IllegalArgumentException("Y lệnh thuốc phải liên kết một dòng đơn thuốc");
        }
        AdmissionOrder entity = new AdmissionOrder();
        entity.admissionId = requireNonNull(admission.getId(), "admission.id");
        entity.orderType = orderType;
        entity.content = InpatientText.require(content, InpatientText.CONTENT_MAX_LENGTH, "content");
        entity.scheduledAt = requireNonNull(scheduledAt, "scheduledAt");
        entity.prescriptionItemId = prescriptionItemId;
        entity.createdBy = requireNonNull(createdBy, "createdBy");
        entity.status = AdmissionOrderStatus.ORDERED;
        return entity;
    }

    /** Y lệnh#2: {@code ORDERED → DONE}. Ghi chú bắt buộc (BR-LT-15). */
    public void complete(Long by, Instant at, String note) {
        requireOrdered();
        String text = InpatientText.require(note, InpatientText.NOTE_MAX_LENGTH, "doneNote");
        Long actor = requireNonNull(by, "by");
        Instant when = requireNonNull(at, "at");
        this.status = AdmissionOrderStatus.DONE;
        this.doneBy = actor;
        this.doneAt = when;
        this.doneNote = text;
    }

    /** Y lệnh#3: {@code ORDERED → CANCELLED}. Lý do bắt buộc (BR-LT-15). */
    public void cancel(String reason) {
        requireOrdered();
        this.cancelReason = InpatientText.require(reason, InpatientText.NOTE_MAX_LENGTH, "cancelReason");
        this.status = AdmissionOrderStatus.CANCELLED;
    }

    /** Y lệnh thuốc chỉ VET thực hiện; bốn loại còn lại VET hoặc CARETAKER (BR-LT-15). Một nguồn cho quy tắc phân quyền. */
    public boolean requiresVet() {
        return orderType == AdmissionOrderType.MEDICATION;
    }

    /** Lớp chặn cuối cho cả hai cạnh ra khỏi {@code ORDERED}; không thay {@code AdmissionOrderTransitionHandler}. */
    public void requireOrdered() {
        if (status != AdmissionOrderStatus.ORDERED) {
            throw new IllegalStateException("Y lệnh đã ở trạng thái cuối " + status);
        }
    }
}
