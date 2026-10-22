package com.petcare.module.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Nhân viên (identity-v1 {@code StaffResponse}; #15, #16, #17 — docs/adr/0030). Đúng 12 trường của contract.
 * <ul>
 *   <li>{@code email}, {@code phone}, {@code role}, {@code status}, {@code isLocked}, {@code mustChangePassword} từ
 *       {@code accounts}; {@code phone} của nhân viên chỉ nằm ở {@code accounts} (BR-TK-01).</li>
 *   <li>{@code isLocked} = cờ khóa của ADMIN ({@code is_locked}, BR-QT-11), không tính khóa tạm do đăng nhập sai
 *       (BR-TK-09) — như {@code AccountSummary}. {@code @JsonProperty} giữ đúng tên trường của contract.</li>
 *   <li>{@code fullName}, {@code branchId} ({@code null} với SUPER_MANAGER — BR-QT-03), {@code specialty},
 *       {@code bio} từ {@code staff_profiles}.</li>
 *   <li>{@code online}: có thao tác trong {@code staff.online_window_minutes} [CFG] (BR-TN-06).</li>
 * </ul>
 * Không có {@code avatarUrl} (contract không có), không có hash, lý do khóa, bộ đếm sai.
 */
public record StaffResponse(
        @Schema(description = "Mã tài khoản", example = "18")
        Long accountId,

        @Schema(description = "Email đăng nhập", example = "chau.le@petcare.vn")
        String email,

        @Schema(description = "Số điện thoại", example = "0912345678")
        String phone,

        @Schema(description = "Họ tên", example = "Lê Minh Châu")
        String fullName,

        @Schema(description = "Chức vụ", example = "VET")
        Role role,

        @Schema(description = "Trạng thái tài khoản", example = "ACTIVE")
        AccountStatus status,

        @Schema(description = "Đang bị ADMIN khóa (BR-QT-11)", example = "false")
        @JsonProperty("isLocked") boolean isLocked,

        @Schema(description = "Chi nhánh; null với SUPER_MANAGER", example = "1")
        Long branchId,

        @Schema(description = "Chuyên môn (chỉ VET)", example = "Nội khoa chó mèo")
        String specialty,

        @Schema(description = "Mô tả ngắn (chỉ VET)", example = "10 năm kinh nghiệm điều trị bệnh nội khoa.")
        String bio,

        @Schema(description = "Chưa đổi mật khẩu ban đầu (BR-TK-17)", example = "false")
        boolean mustChangePassword,

        @Schema(description = "Có thao tác trong staff.online_window_minutes [CFG] gần nhất (BR-TN-06)",
                example = "true")
        boolean online) {
}
