package com.petcare.module.branch.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.petcare.module.branch.entity.BranchStatus;

/** branch-v1 {@code Branch}. */
public record BranchResponse(
        Long branchId,
        String name,
        String address,
        String phone,
        BigDecimal latitude,
        BigDecimal longitude,
        BranchStatus status,
        boolean acceptsAfterHoursEmergency,
        Instant activatedAt) {
}
