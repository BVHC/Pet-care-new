package com.petcare.module.inventory.dto;

import java.util.HashSet;
import java.util.List;

import com.petcare.module.inventory.entity.StockAdjustmentReason;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/stock-adjustments} (inventory-v1 {@code StockAdjustmentRequest}, UC76; BR-KO-06). Lý do
 * {@code OTHER} thiếu ghi chú → 400 {@code BR-KO-06} (service). Mỗi lô chỉ xuất hiện một lần trong phiếu.
 */
public record StockAdjustmentRequest(
        @Schema(description = "Lý do: DAMAGED, EXPIRED, COUNT_MISMATCH, OTHER", example = "DAMAGED")
        @NotNull(message = "Vui lòng chọn lý do")
        StockAdjustmentReason reason,

        @Schema(description = "Ghi chú; bắt buộc khi lý do là OTHER", example = "Rơi vỡ khi vận chuyển")
        @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
        String note,

        @Schema(description = "Các dòng điều chỉnh, 1–200 dòng, mỗi lô một dòng")
        @NotNull(message = "Phiếu cần ít nhất một dòng")
        @Size(min = 1, max = 200, message = "Phiếu cần từ 1 đến 200 dòng")
        @Valid
        List<AdjustmentLineRequest> lines) {

    @Schema(hidden = true)
    @AssertTrue(message = "Mỗi lô chỉ được xuất hiện một lần trong phiếu")
    public boolean isLotsDistinct() {
        if (lines == null) {
            return true;
        }
        HashSet<Long> seen = new HashSet<>();
        return lines.stream().filter(l -> l != null && l.stockLotId() != null).allMatch(l -> seen.add(l.stockLotId()));
    }
}
