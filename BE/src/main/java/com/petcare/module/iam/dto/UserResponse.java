package com.petcare.module.iam.dto;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.UserRole;

import java.util.UUID;

/** docs/api/iam-v1.md C2/C3 — shape PROPOSED. */
public record UserResponse(
        UUID userId,
        UUID accountId,
        String fullName,
        UserRole role,
        UUID organizationId,
        UUID storeId,
        AccountStatus status
) {
}
