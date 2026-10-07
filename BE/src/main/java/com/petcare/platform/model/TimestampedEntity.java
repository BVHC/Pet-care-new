package com.petcare.platform.model;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Cột chung của bảng ROOT, PART, REF (docs/05-erd.md §0): {@code created_at}, {@code updated_at} kiểu TIMESTAMPTZ.
 * Không chứa {@code id} vì khóa chính khác nhau giữa các bảng (IDENTITY, FK dùng làm PK, PK ghép, PK chuỗi);
 * mỗi entity tự khai báo. Thời điểm lấy từ bean {@code Clock} (docs/adr/0015); {@code updated_at} được đặt cả lúc
 * tạo. UPDATE hàng loạt (JPQL/native) không qua listener nên tự đặt {@code updated_at} nếu cần.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class TimestampedEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
