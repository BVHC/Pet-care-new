package com.petcare.module.procurement.dto;

import com.petcare.platform.enums.PurchaseRequestStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PurchaseRequestResponse(
        UUID requestId,
        String requestNumber,
        UUID storeId,
        PurchaseRequestStatus status,
        UUID createdBy,
        UUID approvedBy,
        String rejectionReason,
        List<PurchaseRequestLineResponse> lines,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        LocalDateTime decidedAt,
        LocalDateTime cancelledAt
) {
}
