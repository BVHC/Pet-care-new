package com.petcare.module.iam.dto;

import com.petcare.platform.enums.UserRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** docs/api/iam-v1.md C3 — `POST /users/{id}/role-assignment`. */
public record RoleAssignmentRequest(@NotNull UserRole role, UUID organizationId, UUID storeId) {
}
