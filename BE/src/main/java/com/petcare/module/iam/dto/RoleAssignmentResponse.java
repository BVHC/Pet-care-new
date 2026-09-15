package com.petcare.module.iam.dto;

import com.petcare.platform.enums.UserRole;

import java.util.UUID;

public record RoleAssignmentResponse(UUID userId, UserRole role, UUID organizationId, UUID storeId) {
}
