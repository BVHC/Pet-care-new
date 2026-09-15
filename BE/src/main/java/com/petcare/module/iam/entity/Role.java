package com.petcare.module.iam.entity;

import com.petcare.platform.enums.SecurityScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bảng {@code roles} (docs/06-erd.md §3.1) — catalog đọc-only cho
 * {@code GET /api/roles}. KHÔNG kế thừa {@link com.petcare.platform.model.BaseEntity}
 * vì bảng này không có cột audit/version. RBAC v1 vẫn dùng trực tiếp
 * {@code users.role} (xem {@link User}) — catalog này chỉ seed 9 role chuẩn
 * làm global role (organizationId null) để FE có danh mục hiển thị, chưa gắn
 * với cơ chế phân quyền thật (custom role / user_roles đa-role là TBD, ngoài
 * phạm vi đợt này).
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "scope", nullable = false)
    private SecurityScope scope;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
