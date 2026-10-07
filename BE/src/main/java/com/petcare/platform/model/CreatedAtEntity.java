package com.petcare.platform.model;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Cột chung của bảng LOG (docs/05-erd.md §0): chỉ có {@code created_at}, bảng chỉ thêm mới.
 * Không chứa {@code id}; mỗi entity tự khai báo. Thời điểm lấy từ bean {@code Clock} (docs/adr/0015).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedAtEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
