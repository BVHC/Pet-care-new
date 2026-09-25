package com.petcare.module.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** docs/api/inventory-v1.md C1 — {@code POST /stores/{id}/inventory/count} (CountInventory, RULE-12-02). */
public record CountInventoryRequest(
        @NotNull UUID productId,
        @Min(0) int countedQuantity
) {
}
