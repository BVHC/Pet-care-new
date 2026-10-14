package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả liên kết (identity-v1 {@code LinkResult}, UC07): hồ sơ khách mà tài khoản đang gắn sau khi liên kết — chính là
 * hồ sơ tại quầy đã chọn (BR-TK-19). Khớp {@code GET /api/me.customerId} ngay sau đó.
 */
public record LinkResult(
        @Schema(description = "Hồ sơ khách mà tài khoản từ nay gắn vào", example = "315")
        Long customerId) {
}
