package com.petcare.module.identity.repository;

import java.time.Instant;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

/**
 * Một nhân viên cho {@code StaffResponse} (identity-v1 #15, #16, #17; docs/adr/0030): JOIN {@code staff_profiles} +
 * {@code accounts}. Projection (JPQL constructor expression) — không nạp entity, và chỉ có cột cần cho response:
 * {@code password_hash}, {@code locked_reason}, {@code locked_until}, bộ đếm đăng nhập sai không bao giờ được SELECT.
 * {@code lastSeenAt} chỉ để tính {@code online} (BR-TN-06), không trả thô.
 */
public record StaffDetailRow(
        Long accountId,
        String email,
        String phone,
        String fullName,
        Role role,
        AccountStatus status,
        boolean locked,
        Long branchId,
        String specialty,
        String bio,
        boolean mustChangePassword,
        Instant lastSeenAt) {
}
