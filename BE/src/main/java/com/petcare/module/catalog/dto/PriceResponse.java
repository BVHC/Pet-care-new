package com.petcare.module.catalog.dto;

import java.util.UUID;

public record PriceResponse(
        UUID storeId,
        String price
) {
}
