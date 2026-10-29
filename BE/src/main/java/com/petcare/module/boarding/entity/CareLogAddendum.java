package com.petcare.module.boarding.entity;

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

/** Bảng {@code care_log_addenda} (erd §7, LOG, chỉ thêm): bản bổ sung của một mục nhật ký (BR-LT-11). */
@Getter
@Entity
@Table(name = "care_log_addenda")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareLogAddendum extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "care_log_id", nullable = false, updatable = false)
    private Long careLogId;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    public static CareLogAddendum of(Long careLogId, String content, Long createdBy) {
        CareLogAddendum row = new CareLogAddendum();
        row.careLogId = careLogId;
        row.content = content;
        row.createdBy = createdBy;
        return row;
    }
}
