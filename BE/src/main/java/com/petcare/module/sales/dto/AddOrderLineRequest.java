package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Thêm dịch vụ / hàng vào Order (sales-v1 {@code AddOrderLineRequest}, UC66, UC67). {@code SERVICE} cần
 * {@code serviceId}, {@code GOODS} cần {@code productId} (service kiểm, 400 {@code VALIDATION_FAILED}). Số lượng
 * 1…1 000 000 như dòng phiếu nhập, để thành tiền không tràn {@code BIGINT} (tràn vẫn bị chặn ở entity).
 */
public record AddOrderLineRequest(
        @Schema(description = "Loại dòng: lễ tân thêm GOODS, VET / CARETAKER thêm SERVICE (BR-BH-02)", example = "GOODS")
        @NotNull(message = "Vui lòng chọn loại dòng")
        AddableLineType lineType,

        @Schema(description = "Dịch vụ, bắt buộc khi lineType = SERVICE", example = "4")
        Long serviceId,

        @Schema(description = "Sản phẩm, bắt buộc khi lineType = GOODS", example = "12")
        Long productId,

        @Schema(description = "Số lượng, > 0", example = "2")
        @NotNull(message = "Vui lòng nhập số lượng")
        @Min(value = 1, message = "Số lượng phải lớn hơn 0")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000")
        Integer quantity) {

    /** Hai loại dòng thêm được qua REST (các loại còn lại sinh từ kê đơn, ghi mũi tiêm, trả thú). */
    public enum AddableLineType { SERVICE, GOODS }
}
