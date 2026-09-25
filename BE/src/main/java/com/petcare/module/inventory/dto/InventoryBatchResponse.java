package com.petcare.module.inventory.dto;

import java.time.LocalDate;
import java.util.UUID;

public record InventoryBatchResponse(
        UUID productId,
        String batchNumber,
        LocalDate manufactureDate,
        LocalDate expiryDate,
        int quantity,
        boolean expired
) {
}
