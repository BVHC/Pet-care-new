package com.petcare.module.customer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một địa chỉ trong sổ (customer-v1 {@code Address}). {@code isDefault} cần {@code @JsonProperty} như
 * {@code AccountSummary.isLocked}, để JSON giữ đúng tên của hợp đồng.
 */
public record AddressResponse(
        @Schema(description = "Mã địa chỉ", example = "5")
        Long addressId,

        @Schema(description = "Tên người nhận", example = "Nguyễn Thị Lan")
        String receiverName,

        @Schema(description = "SĐT người nhận", example = "0901234567")
        String receiverPhone,

        @Schema(description = "Số nhà, đường", example = "12 Trần Thái Tông")
        String addressLine,

        @Schema(description = "Phường / xã", example = "Dịch Vọng Hậu")
        String ward,

        @Schema(description = "Tỉnh / thành phố", example = "Hà Nội")
        String province,

        @Schema(description = "Là địa chỉ mặc định (đúng 1 địa chỉ, BR-TK-18)", example = "true")
        @JsonProperty("isDefault") boolean isDefault) {
}
