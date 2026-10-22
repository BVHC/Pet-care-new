package com.petcare.module.visit.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code medical_records} (erd §6, PART của Visit, PK = FK {@code visit_id}): bệnh án của một lượt nhóm
 * {@code MEDICAL} (BR-KB-01). Dòng được tạo ở lần ghi bệnh án đầu tiên (visit-v1 #13, upsert — docs/adr/0064 §1);
 * lượt chưa ghi thì không có dòng. {@code internalNote} chỉ nhân viên xem (BR-KH-07). {@code followUpDate} là ngày tái
 * khám (BR-KB-06); {@code followUpRemindedAt}, {@code followUpCancelledAt} do ST04 / hủy nhắc (BR-TB-06) ghi.
 * {@code lockedAt} = {@code visits.completed_at}, ghi ở Visit#5.
 * <p>
 * PK gán tay (khuôn {@code StaffProfile}): {@code save} đi đường {@code merge} (SELECT rồi INSERT). Không có khóa riêng:
 * mọi bên ghi khóa dòng {@code visits} trước (docs/adr/0064 §7). Method nghiệp vụ ({@code rewrite}, {@code lock}) thêm
 * cùng phần dựng thao tác đó (docs/adr/0058 §1); quy tắc chỉ VET phụ trách ghi, khóa sau {@code COMPLETED}, chỉ nhóm
 * {@code MEDICAL} do service kiểm dưới khóa lượt (docs/adr/0064, bảng đảm bảo).
 */
@Getter
@Entity
@Table(name = "medical_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalRecord extends TimestampedEntity {

    @Id
    @Column(name = "visit_id")
    private Long visitId;

    @Column(name = "examination")
    private String examination;

    @Column(name = "diagnosis")
    private String diagnosis;

    @Column(name = "treatment_plan")
    private String treatmentPlan;

    @Column(name = "internal_note")
    private String internalNote;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "follow_up_reminded_at")
    private Instant followUpRemindedAt;

    @Column(name = "follow_up_cancelled_at")
    private Instant followUpCancelledAt;

    @Column(name = "last_edited_by")
    private Long lastEditedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    /** Bệnh án rỗng của một lượt; service đã kiểm lượt nhóm {@code MEDICAL}, đang {@code IN_PROGRESS}, đúng VET. */
    public static MedicalRecord of(Long visitId) {
        if (visitId == null) {
            throw new IllegalArgumentException("visit_id is required");
        }
        MedicalRecord record = new MedicalRecord();
        record.visitId = visitId;
        return record;
    }

    /**
     * PUT thay toàn bộ 5 trường nội dung (docs/adr/0064 §4, 0065): {@code null} là xóa. Không đụng các cột do ST04 /
     * Visit#5 ghi ({@code followUpRemindedAt}, {@code followUpCancelledAt}, {@code lockedAt}).
     */
    public void rewrite(String examination, String diagnosis, String treatmentPlan, String internalNote,
            LocalDate followUpDate, Long editorId) {
        if (editorId == null) {
            throw new IllegalArgumentException("editorId is required");
        }
        this.examination = examination;
        this.diagnosis = diagnosis;
        this.treatmentPlan = treatmentPlan;
        this.internalNote = internalNote;
        this.followUpDate = followUpDate;
        this.lastEditedBy = editorId;
    }

    /** Nội dung 5 trường giống hệt giá trị đã lưu (PUT không đổi gì — docs/adr/0065 D4). */
    public boolean sameContent(String examination, String diagnosis, String treatmentPlan, String internalNote,
            LocalDate followUpDate) {
        return Objects.equals(this.examination, examination)
                && Objects.equals(this.diagnosis, diagnosis)
                && Objects.equals(this.treatmentPlan, treatmentPlan)
                && Objects.equals(this.internalNote, internalNote)
                && Objects.equals(this.followUpDate, followUpDate);
    }
}
