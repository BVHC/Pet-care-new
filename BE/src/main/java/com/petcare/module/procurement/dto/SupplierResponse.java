package com.petcare.module.procurement.dto;

import com.petcare.platform.enums.SupplierStatus;

import java.util.UUID;

public record SupplierResponse(
        UUID supplierId,
        UUID organizationId,
        String code,
        String name,
        String contactPhone,
        String contactEmail,
        String address,
        SupplierStatus status
) {
}
