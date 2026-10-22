package com.petcare.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/suppliers/{supplierId}} (inventory-v1 {@code UpdateSupplierRequest}, UC73). {@code null} =
 * giữ nguyên; {@code address} rỗng sau {@code strip} = xóa. Tên và SĐT không xóa được. {@code isActive = false} = ngừng
 * hợp tác (BR-KO-02).
 */
public record UpdateSupplierRequest(
        @Schema(description = "Tên mới; null = giữ nguyên", example = "Công ty Thú y Minh Tâm")
        @Size(max = 200, message = "Tên nhà cung cấp tối đa 200 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tên nhà cung cấp không được để trống")
        String name,

        @Schema(description = "SĐT mới; null = giữ nguyên", example = "0281234567")
        @Pattern(regexp = "^0\\d{9,10}$", message = "Số điện thoại phải có 10–11 chữ số và bắt đầu bằng 0")
        String phone,

        @Schema(description = "Địa chỉ mới; null = giữ, rỗng = xóa", example = "12 Nguyễn Huệ, Quận 1, TP.HCM")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        String address,

        @Schema(description = "false = ngừng hợp tác (không chọn được ở phiếu nhập mới); null = giữ nguyên",
                example = "true")
        @JsonProperty("isActive") Boolean isActive) {
}
