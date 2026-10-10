package com.petcare.module.identity.dto;

/**
 * Hồ sơ nhân viên (identity-v1 {@code StaffProfile}). {@code phone} lấy từ {@code accounts.phone} — bảng
 * {@code staff_profiles} không có cột SĐT; nhân viên luôn có SĐT (BR-TK-01, CHECK {@code ck_accounts_phone_by_role}).
 * {@code branchId} null với ADMIN, SUPER_MANAGER (BR-QT-03). {@code specialty}, {@code bio} chỉ VET sửa được.
 */
public record StaffProfileResponse(
        Long accountId,
        String fullName,
        String avatarUrl,
        String phone,
        Long branchId,
        String specialty,
        String bio) {
}
