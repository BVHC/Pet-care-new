package com.petcare.platform.model;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Cột chung của bảng ROOT, PART, REF (docs/05-erd.md §0): {@code created_at}, {@code updated_at} kiểu TIMESTAMPTZ.
 * Không chứa {@code id} vì khóa chính khác nhau giữa các bảng (IDENTITY, FK dùng làm PK, PK ghép, PK chuỗi);
 * mỗi entity tự khai báo.
 */
@Getter
@MappedSuperclass
public abstract class TimestampedEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
