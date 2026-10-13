package com.petcare.module.identity.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.care.api.NotificationApi.Channel;

/**
 * Schema {@code NotificationTemplate} của identity-v1 (#33, #34, #35; UC10 — docs/adr/0037). Tên class khác tên schema
 * chỉ để không trùng entity {@code NotificationTemplate}; JSON giống hệt. {@code subject}, {@code body} là bản
 * <b>đang dùng</b>, không phải bản mặc định; {@code isDefault} = cả hai trùng từng ký tự với bản mặc định.
 * {@code isDefault} cần {@code @JsonProperty} như {@code AddressResponse.isDefault} để JSON giữ đúng tên của hợp đồng.
 * Không có {@code default_*}, {@code updated_by}, {@code updated_at}: hợp đồng không có, lịch sử sửa xem ở audit (UC11).
 */
public record NotificationTemplateResponse(
        String code,
        Channel channel,
        String subject,
        String body,
        List<String> allowedVars,
        List<String> requiredVars,
        @JsonProperty("isDefault") boolean isDefault) {
}
