package com.petcare.platform.model;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Cột chung của bảng LOG (docs/05-erd.md §0): chỉ có {@code created_at}, bảng chỉ thêm mới.
 * Không chứa {@code id}; mỗi entity tự khai báo.
 */
@Getter
@MappedSuperclass
public abstract class CreatedAtEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
