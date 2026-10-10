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

    /**
     * UC06 — nhân viên tự sửa hồ sơ (BR-TK-15, 20; docs/adr/0026). Nhận <b>giá trị cuối</b> của cả bốn trường: service
     * đã quyết giữ / xóa / đổi và kiểm phạm vi (chỉ VET sửa {@code specialty}, {@code bio}) cùng độ dài [CFG]; ở đây
     * không đoán {@code null} hay chuỗi rỗng. {@code branchId} không đổi được ở đây (điều chuyển là UC08). Caller giữ
     * khóa dòng {@code accounts} từ trước khi đọc hồ sơ.
     */
    public void updateSelfProfile(String newFullName, String newAvatarUrl, String newSpecialty, String newBio) {
        if (newFullName == null || newFullName.isBlank()) {
            throw new IllegalArgumentException("full_name is required");
        }
        this.fullName = newFullName;
        this.avatarUrl = newAvatarUrl;
        this.specialty = newSpecialty;
        this.bio = newBio;
    }
}
