package com.petcare.module.catalog.dto;

import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/services} (catalog-v1 {@code CreateServiceRequest}, UC30). BR-SP-04, 06 kiểm ở
 * {@code ServiceCatalogService}.
 */
public record CreateServiceRequest(
        @NotBlank(message = "Tên dịch vụ không được để trống")
        @Size(max = 150, message = "Tên dịch vụ tối đa 150 ký tự")
        String name,

        @NotNull(message = "Thiếu nhóm dịch vụ")
        ServiceGroup group,

        MedicalType medicalType,

        @NotNull(message = "Thiếu giá")
        @PositiveOrZero(message = "Giá không được âm")
        Long price,

        Boolean priceIsFrom,

        String description,

        KennelTypeSpec kennelType) {
}
