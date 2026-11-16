package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * shop-v1 {@code ShipRequest}: đơn vị vận chuyển và mã vận đơn (cột {@code online_orders.carrier} /
 * {@code tracking_code} VARCHAR(100)). Thiếu hoặc rỗng là {@code BR-BH-11} do service kiểm (hợp đồng), không phải
 * Bean Validation; chỉ độ dài kiểm ở đây.
 */
public record ShipRequest(
        @Schema(description = "Đơn vị vận chuyển, bắt buộc, tối đa 100 ký tự", example = "Giao hàng nhanh", maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 100, message = "Đơn vị vận chuyển tối đa 100 ký tự") String carrier,
        @Schema(description = "Mã vận đơn, bắt buộc, tối đa 100 ký tự", example = "GHN123456", maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 100, message = "Mã vận đơn tối đa 100 ký tự") String trackingCode) {
}
