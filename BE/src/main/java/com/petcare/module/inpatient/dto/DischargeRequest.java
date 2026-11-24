package com.petcare.module.inpatient.dto;

import com.petcare.module.inpatient.entity.DischargeOutcome;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Xuất viện (inpatient-v1 {@code DischargeRequest}, #4; BR-LT-14). Chỉ có kết cục và tóm tắt: số ngày, đơn giá, người xuất
 * và thời điểm do server lấy từ ca đã khóa. Tóm tắt rỗng sau {@code strip()} là {@code BR-LT-14} do service kiểm (không
 * dùng {@code @NotBlank}: nó để lọt khoảng trắng Unicode, D072-h) nên ở đây chỉ chặn null và độ dài.
 */
public record DischargeRequest(
        @Schema(description = "Kết cục: `HOME` về nhà hoặc trở lại lưu trú; `DECEASED` thú mất (không tự đánh dấu mất, "
                + "BR-LT-14)", example = "HOME", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn kết cục xuất viện")
        DischargeOutcome outcome,

        @Schema(description = "Tóm tắt điều trị (BR-LT-14), tối đa 2000 ký tự", example = "Hạ sốt, ăn uống bình thường",
                maxLength = 2000, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập tóm tắt điều trị")
        @Size(max = 2000, message = "Tóm tắt điều trị tối đa 2000 ký tự")
        String summary) {
}
