package com.petcare.module.sales.dto;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Xem trước phiếu thu (sales-v1 {@code PaymentPreviewRequest}, #13): cùng ràng buộc danh sách Order như khi thu. */
public record PaymentPreviewRequest(
        @Schema(description = "Các Order PENDING định thu, cùng khách, cùng chi nhánh; tối đa 20, không trùng",
                example = "[101, 102]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "Vui lòng chọn Order cần thu")
        @Size(max = 20, message = "Một phiếu thu tối đa 20 Order")
        List<@NotNull(message = "Mã Order không được trống") @Positive(message = "Mã Order không hợp lệ") Long> orderIds) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "Danh sách Order bị trùng")
    public boolean isDistinct() {
        return orderIds == null || new HashSet<>(orderIds).size() == orderIds.size();
    }
}
