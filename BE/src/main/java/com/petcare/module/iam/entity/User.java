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

import java.util.UUID;

/**
 * Aggregate Root Module 02 (IAM) — bản tối giản, chỉ đủ field phục vụ
 * RegisterAccount (Module 01): tạo hồ sơ Customer đi kèm Account. Không bao
 * gồm Role/Permission đầy đủ của Module 02 — mở rộng khi triển khai module đó.
 * docs/06-erd.md bảng users.
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
