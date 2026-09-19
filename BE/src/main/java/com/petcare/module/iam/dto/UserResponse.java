package com.petcare.module.iam.dto;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.UserRole;

import java.time.LocalDate;
import java.util.UUID;

/**
 * docs/api/iam-v1.md C2/C3. Bao gồm cả {@code gender}/{@code dateOfBirth}/
 * {@code avatarUrl} — đây là 3/4 field mà {@code PATCH /users/me} và
 * {@code PATCH /users/{id}} (target CUSTOMER) cho phép cập nhật (RULE-02-06:
 * Customer "toàn quyền xem" hồ sơ của chính mình); thiếu chúng ở response thì
 * client set xong không đọc lại được.
 */
public record UserResponse(
        UUID userId,
        UUID accountId,
        String fullName,
        String gender,
        LocalDate dateOfBirth,
        String avatarUrl,
        UserRole role,
        UUID organizationId,
        UUID storeId,
        AccountStatus status
) {
}
