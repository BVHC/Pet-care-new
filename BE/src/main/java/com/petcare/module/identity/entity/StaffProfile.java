package com.petcare.module.identity.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code staff_profiles} (erd §1, PART của Account, PK = FK {@code account_id}). {@code branchId} để dạng id,
 * không map quan hệ sang {@code branches} vì bảng đó thuộc module branch (convention 01). NULL với ADMIN,
 * SUPER_MANAGER; A05–A08 bắt buộc có (BR-QT-03, kiểm tra ở ứng dụng).
 */
@Getter
@Entity
@Table(name = "staff_profiles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffProfile extends TimestampedEntity {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "specialty")
    private String specialty;

    @Column(name = "bio")
    private String bio;
}
