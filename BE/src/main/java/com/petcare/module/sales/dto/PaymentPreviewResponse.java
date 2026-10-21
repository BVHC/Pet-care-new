package com.petcare.module.sales.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả xem trước (sales-v1 {@code PaymentPreview}, #13): tổng phải thu và các dòng thiếu tồn nếu thu bây giờ. */
public record PaymentPreviewResponse(
        @Schema(description = "Khách của các Order", example = "5")
        Long customerId,

        @Schema(description = "Chi nhánh của các Order", example = "2")
        Long branchId,

        @Schema(description = "Tổng phải thu, VND", example = "350000")
        long totalAmount,

        @Schema(description = "Các dòng hàng / thuốc thiếu tồn; rỗng nếu thu được")
        List<ShortageLineResponse> shortages) {
}
