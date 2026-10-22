package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/accounts/{id}/lock} và {@code /unlock} (identity-v1 {@code ReasonRequest}, UC09 —
 * docs/adr/0035). BR-QT-11: mỗi lần khóa hoặc mở khóa bắt buộc nhập lý do (03 Tài khoản#7, #8 "Có lý do"). Chỉ toàn dấu
 * cách = thiếu. 500 theo contract; {@code @Size} đếm theo đơn vị UTF-16 nên ký tự ngoài BMP (emoji) tính 2 — chặt hơn
 * contract một chút, không bao giờ lỏng hơn. Service strip trước khi lưu. Trường lạ trong JSON bị bỏ qua.
 */
public record ReasonRequest(
        @Schema(description = "Lý do (bắt buộc, ghi audit)", example = "Tài khoản có dấu hiệu bị chiếm quyền")
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = 500, message = "Lý do tối đa 500 ký tự")
        String reason) {
}
