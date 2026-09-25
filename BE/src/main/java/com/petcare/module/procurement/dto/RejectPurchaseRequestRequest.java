package com.petcare.module.procurement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** RULE-13-02 — "từ chối (REJECTED kèm lý do)": reason bắt buộc, khác RejectInventoryAdjustment (tùy chọn). */
public record RejectPurchaseRequestRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
