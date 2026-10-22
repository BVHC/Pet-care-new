package com.petcare.module.identity.dto;

import com.petcare.module.identity.api.ConfigValueType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Schema {@code SystemConfig} của identity-v1 (#31, #32; UC10 — docs/adr/0036). Tên class khác tên schema chỉ để không
 * trùng entity {@code SystemConfig}; JSON giống hệt. {@code value} là dạng chuẩn hóa đang lưu, cũng là giá trị cache đang
 * dùng sau khi #32 trả 200. {@code minValue}, {@code maxValue} là khoảng <b>tĩnh</b> của BR-QT-13 (khoảng thu hẹp theo
 * ràng buộc chéo chỉ có trong message lỗi); {@code null} = không giới hạn. Không có {@code updated_by},
 * {@code updated_at}: lịch sử sửa xem ở audit (UC11).
 */
public record SystemConfigResponse(
        @Schema(description = "Mã tham số", example = "otp.ttl_minutes")
        String key,

        @Schema(description = "Giá trị hiện tại (dạng chuỗi theo valueType)", example = "5")
        String value,

        @Schema(description = "Kiểu giá trị", example = "INT")
        ConfigValueType valueType,

        @Schema(description = "Giá trị nhỏ nhất cho phép (BR-QT-13)", example = "1")
        String minValue,

        @Schema(description = "Giá trị lớn nhất cho phép (BR-QT-13)", example = "15")
        String maxValue,

        @Schema(description = "Đơn vị", example = "phút")
        String unit,

        @Schema(description = "Ý nghĩa tham số", example = "Thời hạn hiệu lực của mã OTP")
        String description) {
}
