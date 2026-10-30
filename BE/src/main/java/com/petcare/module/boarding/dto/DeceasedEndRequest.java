package com.petcare.module.boarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Kết thúc lưu trú do thú mất (boarding-v1 {@code DeceasedEndRequest}, #18). */
public record DeceasedEndRequest(
        @Schema(description = "Ghi rõ diễn biến (BR-LT-12), tối đa 2000 ký tự", example = "Thú lên cơn co giật lúc 9h, bác sĩ cấp cứu không qua khỏi",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng ghi rõ diễn biến")
        @Size(max = 2000, message = "Diễn biến tối đa 2000 ký tự")
        String note) {
}
