package com.petcare.module.catalog.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body của {@code PATCH /api/vaccine-types/{id}}; trường {@code null} là không đổi. Loài không đổi được. */
public record UpdateVaccineTypeRequest(
        @Pattern(regexp = ".*\\S.*", message = "Tên loại vaccine không được để trống")
        @Size(max = 100, message = "Tên loại vaccine tối đa 100 ký tự")
        String name,

        Boolean isActive) {
}
