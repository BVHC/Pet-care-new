package com.petcare.module.customer.dto;

import com.petcare.module.customer.api.Species;

import io.swagger.v3.oas.annotations.media.Schema;

/** Thú cưng rút gọn trong kết quả tra cứu khách (customer-v1 {@code PetBrief}, BR-KH-10). */
public record PetBriefResponse(
        @Schema(description = "Mã thú cưng", example = "88")
        Long petId,

        @Schema(description = "Tên thú cưng", example = "Mực")
        String name,

        @Schema(description = "Loài", example = "DOG")
        Species species,

        @Schema(description = "Thú đã được đánh dấu đã mất", example = "false")
        boolean deceased) {
}
