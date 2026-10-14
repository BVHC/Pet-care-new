package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ nhân viên (identity-v1 {@code StaffProfile}). {@code phone} lấy từ {@code accounts.phone} — bảng
 * {@code staff_profiles} không có cột SĐT; nhân viên luôn có SĐT (BR-TK-01, CHECK {@code ck_accounts_phone_by_role}).
 * {@code branchId} null với ADMIN, SUPER_MANAGER (BR-QT-03). {@code specialty}, {@code bio} chỉ VET sửa được.
 */
public record StaffProfileResponse(
        @Schema(description = "Mã tài khoản", example = "18")
        Long accountId,

        @Schema(description = "Họ tên", example = "Lê Minh Châu")
        String fullName,

        @Schema(description = "Ảnh đại diện (https); null nếu chưa có",
                example = "https://cdn.petcare.vn/avatars/18.jpg")
        String avatarUrl,

        @Schema(description = "Số điện thoại", example = "0912345678")
        String phone,

        @Schema(description = "Chi nhánh; null với ADMIN, SUPER_MANAGER", example = "1")
        Long branchId,

        @Schema(description = "Chuyên môn (chỉ VET)", example = "Nội khoa chó mèo")
        String specialty,

        @Schema(description = "Mô tả ngắn (chỉ VET)", example = "10 năm kinh nghiệm điều trị bệnh nội khoa.")
        String bio) {
}
