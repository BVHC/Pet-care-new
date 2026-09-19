package com.petcare.module.organization.dto;

import com.petcare.platform.enums.OrganizationStatus;

import java.util.UUID;

public record OrganizationResponse(
        UUID organizationId,
        String code,
        String name,
        String taxCode,
        String address,
        OrganizationStatus status
) {
}
