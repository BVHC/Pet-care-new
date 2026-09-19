package com.petcare.module.organization.dto;

import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.StoreStatus;

import java.util.UUID;

public record StoreResponse(
        UUID storeId,
        UUID organizationId,
        String code,
        String name,
        FacilityType facilityType,
        String address,
        String phone,
        StoreStatus status
) {
}
