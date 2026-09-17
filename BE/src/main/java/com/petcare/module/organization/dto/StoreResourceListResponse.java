package com.petcare.module.organization.dto;

import java.util.List;

public record StoreResourceListResponse(
        List<StoreResourceResponse> items
) {
}
