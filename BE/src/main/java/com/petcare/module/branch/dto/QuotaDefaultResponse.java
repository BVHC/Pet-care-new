package com.petcare.module.branch.dto;

import com.petcare.module.catalog.api.ServiceGroup;

/** branch-v1 {@code QuotaDefault}; {@code defaultQuota} null nghĩa là chưa cấu hình (quota áp dụng là 1, BR-LH-03). */
public record QuotaDefaultResponse(ServiceGroup serviceGroup, Integer defaultQuota) {
}
