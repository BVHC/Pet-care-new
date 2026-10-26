package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/visits/{visitId}/prescriptions} (visit-v1 {@code AddPrescriptionRequest}, UC48, BR-KB-03;
 * docs/adr/0077). Chỉ kiểm hình thức; thuốc có phải thuốc kê đơn đang kinh doanh và tồn khả dụng kiểm ở service dưới
 * khóa. Compact constructor bỏ khoảng trắng hai đầu của liều dùng (cả khoảng trắng Unicode) <b>trước</b> Bean Validation
 * (khuôn {@code ReassignRequest}); {@code @Size} đếm theo UTF-16, không lỏng hơn cột {@code dosage_instructions
 * VARCHAR(500)}. Lượt, chi nhánh, dòng Order, cờ mua ngoài do server quyết định; trường lạ bị bỏ qua.
 */
public record AddPrescriptionRequest(
        @Schema(description = "Thuốc kê đơn (BR-KB-03, BR-SP-01)", example = "77")
        @NotNull(message = "Thiếu thuốc")
        @Positive(message = "Mã thuốc không hợp lệ")
        Long productId,

        @Schema(description = "Số lượng, > 0", example = "10")
        @NotNull(message = "Thiếu số lượng")
        @Positive(message = "Số lượng phải lớn hơn 0")
        Integer quantity,

        @Schema(description = "Liều dùng, in trên đơn", example = "Ngày 2 lần, mỗi lần 1 viên sau ăn")
        @NotBlank(message = "Bắt buộc nhập liều dùng")
        @Size(max = 500, message = "Liều dùng tối đa 500 ký tự")
        String dosageInstructions,

        @Schema(description = "Chỉ gửi khi lần trước bị từ chối vì thiếu tồn (A5); tồn lúc ghi đã đủ thì bị bỏ qua (A26)",
                example = "EXTERNAL_PURCHASE")
        ShortageResolution shortageResolution) {

    public AddPrescriptionRequest {
        dosageInstructions = dosageInstructions == null ? null : dosageInstructions.strip();
    }
}
