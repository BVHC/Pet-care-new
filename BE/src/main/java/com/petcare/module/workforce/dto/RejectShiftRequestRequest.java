package com.petcare.module.workforce.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body của từ chối yêu cầu nghỉ / đổi ca ({@code POST /shift-requests/{id}/reject}, workforce-v1
 * {@code RejectShiftRequestRequest}, #14). Lý do từ chối bắt buộc, ≤ 300 ký tự (khớp
 * {@code shift_requests.decision_note VARCHAR(300)}; BR-NS-06). Chỉ toàn khoảng trắng (kể cả khoảng trắng Unicode như
 * U+3000) = thiếu: dùng {@link StrippedNotBlank} thay {@code @NotBlank} để cùng định nghĩa "trống" với {@code strip()}
 * của entity (docs/adr/0138 mục 53). Service lưu bản đã {@code strip()}. Trường lạ trong JSON bị bỏ qua nên client
 * không đặt được trạng thái, người quyết định hay chi nhánh qua body.
 */
public record RejectShiftRequestRequest(
        @Schema(description = "Lý do từ chối, bắt buộc, tối đa 300 ký tự", example = "Ca này đã đủ người nghỉ",
                requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        @StrippedNotBlank(message = "Vui lòng nhập lý do từ chối")
        @Size(max = 300, message = "Lý do từ chối tối đa 300 ký tự")
        String decisionNote) {
}
