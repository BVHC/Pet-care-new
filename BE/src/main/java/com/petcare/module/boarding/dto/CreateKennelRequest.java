package com.petcare.module.boarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Tạo chuồng (boarding-v1 {@code CreateKennelRequest}, #2). */
public record CreateKennelRequest(
        @Schema(description = "Loại chuồng đang bật tại chi nhánh", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn loại chuồng")
        Long kennelTypeId,

        @Schema(description = "Mã chuồng, duy nhất trong chi nhánh (BR-LT-01)", example = "C-01",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập mã chuồng")
        @Size(max = 20, message = "Mã chuồng tối đa 20 ký tự")
        String code,

        @Schema(description = "Ghi chú", example = "Gần cửa sổ")
        @Size(max = 300, message = "Ghi chú tối đa 300 ký tự")
        String note) {
}
