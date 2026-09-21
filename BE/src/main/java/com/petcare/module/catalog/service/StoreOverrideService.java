package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.AvailabilityRequest;
import com.petcare.module.catalog.dto.AvailabilityResponse;
import com.petcare.module.catalog.dto.PriceRequest;
import com.petcare.module.catalog.dto.PriceResponse;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

/**
 * docs/api/catalog-v1.md C2 — ConfigureProductPrice/ConfigureServicePrice/
 * ConfigureServiceAvailability (RULE-05-04, RULE-05-05, RULE-05-07).
 */
public interface StoreOverrideService {

    PriceResponse configureProductPrice(UUID storeId, UUID productId, PriceRequest request, UserPrincipal actor);

    PriceResponse configureServicePrice(UUID storeId, UUID serviceId, PriceRequest request, UserPrincipal actor);

    AvailabilityResponse configureServiceAvailability(UUID storeId, UUID serviceId, AvailabilityRequest request, UserPrincipal actor);

    /**
     * RULE-05-07 — khởi tạo bulk override giá/khả dụng cho toàn bộ Product/Service của Organization
     * tại Store, kế thừa base_price/is_active gốc. Idempotent: chỉ tạo override còn thiếu, không
     * ghi đè override đã tồn tại. Điểm tích hợp dành cho {@code ActivateStore} (Module 03) gọi khi
     * FSM Store được triển khai — hiện KHÔNG có caller production nào (Store chưa có transition
     * ACTIVATE), chỉ IT test gọi trực tiếp để dựng dữ liệu.
     */
    void initializeOverridesForStore(UUID organizationId, UUID storeId);
}
