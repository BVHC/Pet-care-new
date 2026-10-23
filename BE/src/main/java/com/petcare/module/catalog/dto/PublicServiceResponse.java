package com.petcare.module.catalog.dto;

import java.util.List;

import com.petcare.module.catalog.api.ServiceGroup;

/**
 * catalog-v1 {@code PublicService} ({@code GET /api/public/services}, UC15; BR-CK-01, 02). {@code price} là giá khởi
 * điểm ("Từ X đ") khi {@code priceIsFrom}; loại chuồng (nhóm {@code BOARDING}) là giá theo đêm. {@code branchIds} là mọi
 * chi nhánh ACTIVE đang bật dịch vụ.
 */
public record PublicServiceResponse(
        Long serviceId,
        String name,
        ServiceGroup group,
        long price,
        boolean priceIsFrom,
        String description,
        KennelTypeSpec kennelType,
        List<Long> branchIds) {
}
