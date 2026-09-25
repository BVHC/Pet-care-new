package com.petcare.module.procurement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** docs/api/procurement-v1.md — ManageSupplier (RULE-13-04). organizationId lấy từ actor.getOrganizationId(). */
public record CreateSupplierRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 20) String contactPhone,
        @Email @Size(max = 100) String contactEmail,
        String address
) {
}
