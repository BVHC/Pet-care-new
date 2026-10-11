package com.petcare.module.customer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Một địa chỉ trong sổ (customer-v1 {@code Address}). {@code isDefault} cần {@code @JsonProperty} như
 * {@code AccountSummary.isLocked}, để JSON giữ đúng tên của hợp đồng.
 */
public record AddressResponse(
        Long addressId,
        String receiverName,
        String receiverPhone,
        String addressLine,
        String ward,
        String province,
        @JsonProperty("isDefault") boolean isDefault) {
}
