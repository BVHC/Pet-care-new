package com.petcare.module.sales.dto;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.petcare.module.sales.entity.PaymentMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Thu tiền (sales-v1 {@code CreatePaymentRequest}, #14; BR-TG-02, 03; docs/adr/0064). Một phiếu thu tối đa 20 Order,
 * không trùng id; {@code amount} phải bằng tổng các Order (service kiểm, BR-TG-02) và được phép bằng 0 (Order 0đ).
 */
public record CreatePaymentRequest(
        @Schema(description = "Các Order PENDING cần thu, cùng khách, cùng chi nhánh; tối đa 20, không trùng",
                example = "[101, 102]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "Vui lòng chọn Order cần thu")
        @Size(max = 20, message = "Một phiếu thu tối đa 20 Order")
        List<@NotNull(message = "Mã Order không được trống") @Positive(message = "Mã Order không hợp lệ") Long> orderIds,

        @Schema(description = "Phương thức thanh toán", example = "CASH",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn phương thức thanh toán")
        PaymentMethod method,

        @Schema(description = "Số tiền thu, VND, bằng tổng các Order (không thu một phần)", example = "350000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập số tiền thu")
        @PositiveOrZero(message = "Số tiền thu không được âm")
        Long amount) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "Danh sách Order bị trùng")
    public boolean isDistinct() {
        return orderIds == null || new HashSet<>(orderIds).size() == orderIds.size();
    }
}
