package com.petcare.module.identity.dto;

import com.petcare.module.identity.api.ConfigValueType;

/**
 * Schema {@code SystemConfig} của identity-v1 (#31, #32; UC10 — docs/adr/0036). Tên class khác tên schema chỉ để không
 * trùng entity {@code SystemConfig}; JSON giống hệt. {@code value} là dạng chuẩn hóa đang lưu, cũng là giá trị cache đang
 * dùng sau khi #32 trả 200. {@code minValue}, {@code maxValue} là khoảng <b>tĩnh</b> của BR-QT-13 (khoảng thu hẹp theo
 * ràng buộc chéo chỉ có trong message lỗi); {@code null} = không giới hạn. Không có {@code updated_by},
 * {@code updated_at}: lịch sử sửa xem ở audit (UC11).
 */
public record SystemConfigResponse(
        String key,
        String value,
        ConfigValueType valueType,
        String minValue,
        String maxValue,
        String unit,
        String description) {
}
