package com.petcare.module.workforce.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body của hủy phân ca ({@code POST /shift-assignments/{id}/cancel}, workforce-v1 {@code ShiftReasonRequest}, #6) và
 * của ghi vắng đột xuất (#7, T59). Lý do bắt buộc, ≤ 300 ký tự (khớp {@code shift_assignments.reason VARCHAR(300)};
 * BR-NS-02, BR-NS-07). Chỉ toàn khoảng trắng (kể cả khoảng trắng Unicode như U+3000) = thiếu: dùng
 * {@link StrippedNotBlank} thay {@code @NotBlank} để cùng định nghĩa "trống" với {@code strip()} của entity. {@code @Size} đếm theo đơn vị UTF-16 nên ký tự ngoài BMP tính 2: chặt
 * hơn cột một chút, không bao giờ lỏng hơn. Service lưu bản đã {@code strip()}. Không dùng tên {@code ReasonRequest}:
 * {@code identity.dto.ReasonRequest} đã có (tối đa 500) và springdoc gộp schema theo tên đơn giản (docs/adr/0149 bước 4).
 * Trường lạ trong JSON bị bỏ qua nên client không đặt được trạng thái, chi nhánh hay người xử lý qua body.
 */
public record ShiftReasonRequest(
        @Schema(description = "Lý do, bắt buộc, tối đa 300 ký tự", example = "Nhân viên xin đổi lịch do việc gia đình",
                requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        @StrippedNotBlank(message = "Vui lòng nhập lý do")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
