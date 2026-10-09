package com.petcare.module.branch.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/branches/{id}}; trường {@code null} là không đổi. Gồm cờ nhận cấp cứu ngoài giờ
 * (BR-CN-05), chỉ SUPER_MANAGER sửa được.
 */
public record UpdateBranchRequest(
        @Pattern(regexp = ".*\\S.*", message = "Tên chi nhánh không được để trống")
        @Size(max = 150, message = "Tên chi nhánh tối đa 150 ký tự")
        String name,

        @Pattern(regexp = ".*\\S.*", message = "Địa chỉ không được để trống")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        String address,

        @Pattern(regexp = ".*\\S.*", message = "Số điện thoại không được để trống")
        @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự")
        String phone,

        @DecimalMin(value = "-90", message = "Vĩ độ phải từ -90 đến 90")
        @DecimalMax(value = "90", message = "Vĩ độ phải từ -90 đến 90")
        @Digits(integer = 2, fraction = 6, message = "Vĩ độ tối đa 6 chữ số thập phân")
        BigDecimal latitude,

        @DecimalMin(value = "-180", message = "Kinh độ phải từ -180 đến 180")
        @DecimalMax(value = "180", message = "Kinh độ phải từ -180 đến 180")
        @Digits(integer = 3, fraction = 6, message = "Kinh độ tối đa 6 chữ số thập phân")
        BigDecimal longitude,

        Boolean acceptsAfterHoursEmergency) {
}
