package com.petcare.module.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

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
        Long accountId,
        String email,
        String phone,
        String fullName,
        Role role,
        AccountStatus status,
        @JsonProperty("isLocked") boolean isLocked,
        Long branchId,
        String specialty,
        String bio,
        boolean mustChangePassword,
        boolean online) {
}
