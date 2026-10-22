package com.petcare.module.visit.entity;

import com.petcare.platform.model.CreatedAtEntity;

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
 * Bảng {@code medical_record_addenda} (erd §6, LOG): bản bổ sung sau khi bệnh án khóa (BR-KB-01). Chỉ thêm mới: mọi cột
 * {@code updatable = false}, không setter, service không xóa (G9, docs/adr/0066). {@code internal} = bổ sung cho ghi chú
 * nội bộ, khách không thấy [ERD]. Chỉ thêm khi lượt đã {@code COMPLETED}, do VET chi nhánh nơi khám — service kiểm
 * dưới khóa lượt (visit-v1 A7).
 */
@Getter
@Entity
@Table(name = "medical_record_addenda")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalRecordAddendum extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medical_record_id", nullable = false, updatable = false)
    private Long medicalRecordId;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "is_internal", nullable = false, updatable = false)
    private boolean internal;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    /** Một bản bổ sung; {@code medicalRecordId} = {@code visit_id}; {@code created_at} theo {@code Clock}. */
    public static MedicalRecordAddendum of(Long medicalRecordId, String content, boolean internal, Long createdBy) {
        MedicalRecordAddendum addendum = new MedicalRecordAddendum();
        addendum.medicalRecordId = medicalRecordId;
        addendum.content = content;
        addendum.internal = internal;
        addendum.createdBy = createdBy;
        return addendum;
    }
}
