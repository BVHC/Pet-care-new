package com.petcare.module.branch.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/branches} (branch-v1 {@code CreateBranchRequest}, UC12). Tên, địa chỉ, SĐT, tọa độ là bắt
 * buộc (BR-CN-01, Chi nhánh#1); cột {@code latitude}/{@code longitude} là {@code NUMERIC(9,6)}.
 */
public record CreateBranchRequest(
        @NotBlank(message = "Tên chi nhánh không được để trống")
        @Size(max = 150, message = "Tên chi nhánh tối đa 150 ký tự")
        String name,

        @NotBlank(message = "Địa chỉ không được để trống")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        String address,

        @NotBlank(message = "Số điện thoại không được để trống")
        @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự")
        String phone,

        @NotNull(message = "Thiếu vĩ độ")
        @DecimalMin(value = "-90", message = "Vĩ độ phải từ -90 đến 90")
        @DecimalMax(value = "90", message = "Vĩ độ phải từ -90 đến 90")
        @Digits(integer = 2, fraction = 6, message = "Vĩ độ tối đa 6 chữ số thập phân")
        BigDecimal latitude,

        @NotNull(message = "Thiếu kinh độ")
        @DecimalMin(value = "-180", message = "Kinh độ phải từ -180 đến 180")
        @DecimalMax(value = "180", message = "Kinh độ phải từ -180 đến 180")
        @Digits(integer = 3, fraction = 6, message = "Kinh độ tối đa 6 chữ số thập phân")
        BigDecimal longitude,

        Boolean acceptsAfterHoursEmergency) {
}
