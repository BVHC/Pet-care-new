package com.petcare.module.inpatient.dto;

import java.time.Instant;

import com.petcare.module.inpatient.entity.AdmissionOrderType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Ra y lệnh (inpatient-v1 {@code CreateAdmissionOrderRequest}, #5; BR-LT-15). Người ra y lệnh và trạng thái do server đặt.
 * Nội dung rỗng sau {@code strip()} và các quy tắc về dòng đơn thuốc là {@code BR-LT-15} do service kiểm sau 409 (không
 * dùng {@code @NotBlank}: nó để lọt khoảng trắng Unicode, D072-h) nên ở đây chỉ chặn null, độ dài và id không dương.
 */
public record CreateAdmissionOrderRequest(
        @Schema(description = "Loại y lệnh; `MEDICATION` bắt buộc có `prescriptionItemId`, các loại khác cấm",
                example = "MEDICATION", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn loại y lệnh")
        AdmissionOrderType orderType,

        @Schema(description = "Nội dung y lệnh (một y lệnh là một lần thực hiện), tối đa 500 ký tự",
                example = "Tiêm kháng sinh theo đơn, buổi sáng", maxLength = 500,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập nội dung y lệnh")
        @Size(max = 500, message = "Nội dung y lệnh tối đa 500 ký tự")
        String content,

        @Schema(description = "Giờ thực hiện dự kiến; không giới hạn (cho phép ghi bù)",
                example = "2026-11-25T01:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập giờ thực hiện dự kiến")
        Instant scheduledAt,

        @Schema(description = "Dòng đơn thuốc của Visit (kê qua visit-v1 #16), không phải dòng mua ngoài. Bắt buộc với "
                + "`MEDICATION`, cấm với loại khác (BR-LT-15)", example = "7001", nullable = true)
        @Positive(message = "Mã dòng đơn thuốc phải lớn hơn 0")
        Long prescriptionItemId) {
}
