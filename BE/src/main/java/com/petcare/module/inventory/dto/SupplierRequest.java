package com.petcare.module.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body của {@code POST /api/suppliers} (inventory-v1 {@code SupplierRequest}, UC73; BR-KO-02). */
public record SupplierRequest(
        @Schema(description = "Tên nhà cung cấp", example = "Công ty Thú y Minh Tâm")
        @NotBlank(message = "Vui lòng nhập tên nhà cung cấp")
        @Size(max = 200, message = "Tên nhà cung cấp tối đa 200 ký tự")
        String name,

        @Schema(description = "SĐT, 10–11 chữ số, bắt đầu bằng 0", example = "0281234567")
        @NotNull(message = "Vui lòng nhập số điện thoại")
        @Pattern(regexp = "^0\\d{9,10}$", message = "Số điện thoại phải có 10–11 chữ số và bắt đầu bằng 0")
        String phone,

        @Schema(description = "Địa chỉ", example = "12 Nguyễn Huệ, Quận 1, TP.HCM")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        String address) {
}
