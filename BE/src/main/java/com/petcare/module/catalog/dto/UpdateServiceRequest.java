package com.petcare.module.catalog.dto;

import com.petcare.module.catalog.api.MedicalType;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/services/{id}}; trường {@code null} là không đổi. Nhóm dịch vụ không đổi được
 * (catalog-v1 A1).
 */
public record UpdateServiceRequest(
        @Pattern(regexp = ".*\\S.*", message = "Tên dịch vụ không được để trống")
        @Size(max = 150, message = "Tên dịch vụ tối đa 150 ký tự")
        String name,

        MedicalType medicalType,

        @PositiveOrZero(message = "Giá không được âm")
        Long price,

        Boolean priceIsFrom,

        String description,

        Boolean isActive,

        KennelTypeSpec kennelType) {
}
