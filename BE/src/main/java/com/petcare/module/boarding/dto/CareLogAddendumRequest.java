package com.petcare.module.boarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Thêm bản bổ sung nhật ký (boarding-v1 {@code AddendumRequest}, #22). */
public record CareLogAddendumRequest(
        @Schema(description = "Nội dung bổ sung", example = "Chiều bé ăn lại bình thường",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập nội dung bổ sung")
        @Size(max = 2000, message = "Nội dung bổ sung tối đa 2000 ký tự")
        String content) {
}
