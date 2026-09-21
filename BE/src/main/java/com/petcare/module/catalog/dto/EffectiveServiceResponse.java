package com.petcare.module.catalog.dto;

import java.util.UUID;

/**
 * docs/api/openapi/catalog-v1.yaml #EffectiveServicePage.items — {@code GET
 * /stores/{id}/services} (RULE-05-06). {@code price}: override nếu có, else
 * {@code services.base_price}. {@code isActive}: override {@code store_services.is_active}
 * nếu override đã tồn tại; ASSUMPTION — nếu override CHƯA tồn tại (Store chưa được
 * {@code initializeOverridesForStore}, RULE-05-07), coi là {@code false} (chưa được Store cấu
 * hình mở bán), không fallback về {@code services.is_active} gốc — override là cổng duy nhất
 * quyết định dịch vụ có phục vụ tại Store đó hay không (RULE-05-04).
 */
public record EffectiveServiceResponse(
        UUID serviceId,
        String name,
        String price,
        boolean isActive,
        int durationMinutes
) {
}
