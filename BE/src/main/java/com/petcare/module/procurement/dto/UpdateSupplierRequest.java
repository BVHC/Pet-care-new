package com.petcare.module.procurement.dto;

import com.petcare.platform.enums.SupplierStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** null = giữ nguyên (partial update), cùng kiểu UpdateProductRequest. */
public record UpdateSupplierRequest(
        @Size(max = 255) String name,
        @Size(max = 20) String contactPhone,
        @Email @Size(max = 100) String contactEmail,
        String address,
        SupplierStatus status
) {
}
