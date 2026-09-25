package com.petcare.module.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** docs/api/inventory-v1.md C1 — {@code POST /stores/{id}/inventory/issue} (IssueInventory, RULE-12-05). */
public record IssueInventoryRequest(
        @NotNull UUID productId,
        @Positive int quantity,
        @Size(max = 255) String reason
) {
}
