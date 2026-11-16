package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code FulfillmentBranch}: chi nhánh ACTIVE còn đủ hàng cho mọi dòng của giỏ. */
public record FulfillmentBranchResponse(
        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED) Long branchId,
        @Schema(example = "Pet Care Quận 1", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(example = "12 Lê Lợi, Quận 1, TP.HCM", requiredMode = Schema.RequiredMode.REQUIRED) String address) {
}
