package com.petcare.module.catalog.dto;

import com.petcare.module.customer.api.Species;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body của {@code POST /api/vaccine-types} (catalog-v1 {@code VaccineTypeRequest}, UC31). */
public record VaccineTypeRequest(
        @NotBlank(message = "Tên loại vaccine không được để trống")
        @Size(max = 100, message = "Tên loại vaccine tối đa 100 ký tự")
        String name,

        @NotNull(message = "Thiếu loài áp dụng")
        Species species) {
}
