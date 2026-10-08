package com.petcare.module.catalog.dto;

import com.petcare.module.customer.api.Species;

import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code POST /api/vaccination-protocols} (catalog-v1 {@code CreateProtocolRequest}, UC31). Thiếu trường
 * bị chặn ở đây; khoảng cách ≤ 0 và các giới hạn số kiểm ở service để trả {@code BR-SP-02}.
 */
public record CreateProtocolRequest(
        @NotNull(message = "Thiếu loài")
        Species species,

        @NotNull(message = "Thiếu loại vaccine")
        Long vaccineTypeId,

        @NotNull(message = "Thiếu mũi thứ mấy")
        Integer doseNumber,

        @NotNull(message = "Thiếu khoảng cách đến mũi kế tiếp")
        Integer intervalDays,

        @NotNull(message = "Thiếu tuổi tối thiểu")
        Integer minAgeWeeks,

        @NotNull(message = "Thiếu cờ bắt buộc khi lưu trú")
        Boolean requiredForBoarding) {
}
