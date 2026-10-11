package com.petcare.module.branch.dto;

import com.petcare.module.catalog.api.ServiceGroup;

/** branch-v1 {@code BranchServiceItem}: một dịch vụ đang kinh doanh (kể cả loại chuồng) và cờ bật tại chi nhánh. */
public record BranchServiceItem(Long serviceId, String name, ServiceGroup group, boolean enabled) {
}
