package com.petcare.module.boarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Sửa mã / ghi chú chuồng (boarding-v1 {@code UpdateKennelRequest}, #3); trường không gửi thì giữ nguyên. */
public record UpdateKennelRequest(
        @Schema(description = "Mã chuồng mới, duy nhất trong chi nhánh (BR-LT-01)", example = "C-02")
        @Size(max = 20, message = "Mã chuồng tối đa 20 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Mã chuồng không được để trống")
        String code,

        @Schema(description = "Ghi chú mới", example = "Gần cửa sổ")
        @Size(max = 300, message = "Ghi chú tối đa 300 ký tự")
        String note) {
}
