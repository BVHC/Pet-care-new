package com.petcare.module.customer.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Chi tiết hồ sơ khách kèm thú cưng (customer-v1 {@code CustomerDetail}, UC22, UC23). Thú theo mã tăng dần. */
public record CustomerDetailResponse(
        @Schema(description = "Hồ sơ khách")
        CustomerProfileResponse customer,

        @Schema(description = "Thú cưng của khách, kể cả đã mất")
        List<PetResponse> pets) {
}
