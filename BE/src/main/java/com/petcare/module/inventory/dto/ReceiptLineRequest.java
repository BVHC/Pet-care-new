package com.petcare.module.inventory.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Một dòng phiếu nhập (inventory-v1 {@code ReceiptLineRequest}, BR-KO-03). Số lượng 1…1 000 000 và giá nhập 0…1 tỷ để
 * tổng tiền phiếu không tràn {@code BIGINT}. Số lô / hạn dùng để trống được ở phiếu nháp; bắt buộc khi xác nhận với
 * sản phẩm quản lý hạn dùng (kiểm ở service, BR-KO-03).
 */
public record ReceiptLineRequest(
        @Schema(description = "Mã sản phẩm", example = "12")
        @NotNull(message = "Vui lòng chọn sản phẩm")
        Long productId,

        @Schema(description = "Số lượng nhập, > 0", example = "50")
        @NotNull(message = "Vui lòng nhập số lượng")
        @Min(value = 1, message = "Số lượng phải lớn hơn 0")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000")
        Integer quantity,

        @Schema(description = "Giá nhập mỗi đơn vị (VND)", example = "85000")
        @NotNull(message = "Vui lòng nhập giá nhập")
        @Min(value = 0, message = "Giá nhập không được âm")
        @Max(value = 1_000_000_000L, message = "Giá nhập tối đa 1.000.000.000")
        Long unitCost,

        @Schema(description = "Số lô; bắt buộc khi xác nhận với sản phẩm quản lý hạn dùng", example = "LOT-2610-A")
        @Size(max = 50, message = "Số lô tối đa 50 ký tự")
        String lotNumber,

        @Schema(description = "Hạn dùng, sau ngày nhập; bắt buộc khi xác nhận với sản phẩm quản lý hạn dùng",
                example = "2027-10-01")
        LocalDate expiryDate) {
}
