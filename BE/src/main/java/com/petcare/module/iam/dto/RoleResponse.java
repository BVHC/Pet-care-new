package com.petcare.module.iam.dto;

import com.petcare.platform.enums.SecurityScope;

import java.util.UUID;

/** docs/api/iam-v1.md A12 — `GET /roles`. */
public record RoleResponse(UUID roleId, String code, String name, SecurityScope scope, UUID organizationId) {
}
