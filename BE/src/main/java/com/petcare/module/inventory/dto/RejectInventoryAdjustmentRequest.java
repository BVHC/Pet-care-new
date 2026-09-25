package com.petcare.module.inventory.dto;

import jakarta.validation.constraints.Size;

public record RejectInventoryAdjustmentRequest(
        @Size(max = 500) String reason
) {
}
