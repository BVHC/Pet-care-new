package com.petcare.module.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Một dòng điều chỉnh (inventory-v1 {@code AdjustmentLineRequest}): âm = giảm, dương = tăng, khác 0, |giá trị| ≤ 1.000.000. */
public record AdjustmentLineRequest(
        @Schema(description = "Mã lô (phải thuộc chi nhánh của người gọi); sản phẩm không quản lý hạn dùng dùng lô "
                + "mặc định (lấy id từ danh sách lô, A4)", example = "77")
        @NotNull(message = "Vui lòng chọn lô")
        Long stockLotId,

        @Schema(description = "Số lượng thay đổi, khác 0; âm = giảm, dương = tăng", example = "-3")
        @NotNull(message = "Vui lòng nhập số lượng điều chỉnh")
        @Min(value = -1_000_000, message = "Số lượng điều chỉnh tối thiểu -1.000.000")
        @Max(value = 1_000_000, message = "Số lượng điều chỉnh tối đa 1.000.000")
        Integer quantityDelta) {

    @Schema(hidden = true)
    @AssertTrue(message = "Số lượng điều chỉnh phải khác 0")
    public boolean isNonZero() {
        return quantityDelta == null || quantityDelta != 0;
    }
}
