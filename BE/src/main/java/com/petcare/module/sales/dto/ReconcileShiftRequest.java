package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Đối soát ca (sales-v1 {@code ReconcileShiftRequest}, #21). {@code countedCash} bắt buộc khi ca tự chốt chưa có số
 * thực đếm (BR-TG-05, service kiểm); gửi thì ghi đè số lễ tân đã nhập, audit giữ số cũ (docs/adr/0064).
 */
public record ReconcileShiftRequest(
        @Schema(description = "Tiền mặt thực đếm, VND; bắt buộc nếu ca tự chốt chưa có số này", example = "480000")
        @PositiveOrZero(message = "Số tiền thực đếm không được âm")
        Long countedCash,

        @Schema(description = "Ghi chú đối soát (chênh lệch, sai sót phát hiện sau khi thu), tối đa 1000 ký tự",
                example = "Thiếu 20.000đ, lễ tân xác nhận trả nhầm tiền thừa",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập ghi chú đối soát")
        @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
        String note) {
}
