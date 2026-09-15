package com.petcare.module.iam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * Bảng {@code permissions} (docs/06-erd.md §3.1) — catalog đọc-only cho
 * {@code GET /api/permissions}. Hiện KHÔNG có module nghiệp vụ nào định nghĩa
 * permission code thật (RBAC v1 chỉ dùng role đơn qua {@code users.role}) nên
 * bảng này chưa được seed — endpoint trả mảng rỗng cho tới khi có module cần.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
public class Permission {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "module", nullable = false, length = 50)
    private String module;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
