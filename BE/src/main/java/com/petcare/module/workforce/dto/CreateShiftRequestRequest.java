package com.petcare.module.workforce.dto;

import com.petcare.module.workforce.entity.ShiftRequestType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Gửi yêu cầu nghỉ / đổi ca (workforce-v1 {@code CreateShiftRequestRequest}, #9; BR-NS-04). Cố ý chỉ có bốn trường của
 * hợp đồng: không có {@code requesterId} (luôn là người gọi), {@code status}, {@code decidedBy}, {@code closedAt} nên
 * client không đặt được chúng qua body (trường lạ trong JSON bị bỏ qua, docs/adr/0138 bước 6).
 * <p>
 * {@code targetShiftDefinitionId} bắt buộc với {@code SWAP} và không được có với {@code LEAVE}: hợp đồng đặt hai điều
 * kiện đó ở {@code BR-NS-04} (400), không phải {@code VALIDATION_FAILED}, nên service kiểm chứ không dùng Bean Validation.
 * {@code @Size} đếm theo đơn vị UTF-16 trên bản thô nên bản đã {@code strip()} không bao giờ vượt cột
 * {@code VARCHAR(300)}.
 */
public record CreateShiftRequestRequest(
        @Schema(description = "Phân ca SCHEDULED của chính người gọi", example = "31",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn phân ca")
        Long shiftAssignmentId,

        @Schema(description = "LEAVE = nghỉ ca đó; SWAP = đổi sang ca khác cùng ngày", example = "SWAP",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn loại yêu cầu")
        ShiftRequestType type,

        @Schema(description = "Bắt buộc với SWAP, không được có với LEAVE", example = "8", nullable = true)
        Long targetShiftDefinitionId,

        @Schema(description = "Lý do, bắt buộc, tối đa 300 ký tự", example = "Có việc gia đình đột xuất",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
