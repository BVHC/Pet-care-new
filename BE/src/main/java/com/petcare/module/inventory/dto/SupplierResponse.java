package com.petcare.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Nhà cung cấp (inventory-v1 {@code Supplier}). */
public record SupplierResponse(
        @Schema(description = "Mã nhà cung cấp", example = "3")
        Long supplierId,

        @Schema(description = "Tên", example = "Công ty Thú y Minh Tâm")
        String name,

        @Schema(description = "SĐT", example = "0281234567")
        String phone,

        @Schema(description = "Địa chỉ; null nếu chưa khai", example = "12 Nguyễn Huệ, Quận 1, TP.HCM")
        String address,

        @Schema(description = "Còn hợp tác; false thì không chọn được ở phiếu nhập mới (BR-KO-02)", example = "true")
        @JsonProperty("isActive") boolean isActive) {
}
