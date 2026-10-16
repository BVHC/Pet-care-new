package com.petcare.module.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code POST /api/pets/{petId}/transfer-owner} (customer-v1 {@code TransferOwnerRequest}, UC26; BR-KH-08).
 * Chủ mới phải đã có hồ sơ. {@code previousOwnerConfirmed = true}: lễ tân xác nhận chủ cũ có mặt hoặc đã đồng ý — hệ
 * thống không có kênh xác nhận riêng (customer-v1 A4).
 */
public record TransferOwnerRequest(
        @Schema(description = "Hồ sơ khách của chủ mới (phải đã tồn tại)", example = "412")
        @NotNull(message = "Vui lòng chọn chủ mới")
        Long newCustomerId,

        @Schema(description = "Phải true: lễ tân đã xác nhận chủ cũ có mặt hoặc đã đồng ý (A4)", example = "true")
        @NotNull(message = "Vui lòng xác nhận chủ cũ")
        @AssertTrue(message = "Cần xác nhận chủ cũ có mặt hoặc đã đồng ý")
        Boolean previousOwnerConfirmed) {
}
