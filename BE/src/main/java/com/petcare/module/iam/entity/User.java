package com.petcare.module.iam.entity;

import com.petcare.platform.enums.UserRole;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate Root Module 02 (IAM) — docs/06-erd.md bảng users. RBAC v1 dùng
 * trực tiếp cột {@code role} (không có bảng Role/Permission/RolePermission
 * riêng — ASSUMPTION A3 docs/api/iam-v1.md, user_roles đa-role là TBD).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false)
    private UserRole role = UserRole.CUSTOMER;

    @Column(name = "staff_code", length = 50)
    private String staffCode;

    public User(UUID accountId, String fullName) {
        this.accountId = accountId;
        this.fullName = fullName;
        this.role = UserRole.CUSTOMER;
    }
}
