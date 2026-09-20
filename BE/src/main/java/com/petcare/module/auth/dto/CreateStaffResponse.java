package com.petcare.module.auth.dto;

import com.petcare.platform.enums.AccountStatus;

import java.util.UUID;

public record CreateStaffResponse(UUID accountId, UUID userId, AccountStatus status, boolean mustChangePassword) {
}
