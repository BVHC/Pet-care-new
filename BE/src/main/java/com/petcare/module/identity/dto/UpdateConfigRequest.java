package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PUT /api/system-configs/{key}} (identity-v1 {@code UpdateConfigRequest}, UC10 — docs/adr/0036). Chỉ
 * kiểm hình thức; kiểu, min–max và ràng buộc chéo phụ thuộc key nên kiểm ở service (BR-QT-13, convention 06). 255 =
 * {@code system_configs.value} (erd §1). Số / boolean JSON được Jackson ép thành chuỗi; trường lạ bị bỏ qua.
 */
public record UpdateConfigRequest(
        @Schema(description = "Giá trị mới, đúng kiểu và trong khoảng min–max (BR-QT-13)", example = "10")
        @NotBlank(message = "Vui lòng nhập giá trị")
        @Size(max = 255, message = "Giá trị tối đa 255 ký tự")
        String value) {
}
