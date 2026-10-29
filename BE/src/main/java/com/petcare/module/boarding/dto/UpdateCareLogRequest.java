package com.petcare.module.boarding.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Sửa nhật ký trong 1 giờ (boarding-v1 {@code UpdateCareLogRequest}, #21). Trường vắng / {@code null} giữ nguyên, chuỗi
 * trống xóa trường; không sửa được {@code isAbnormal} và ngày (docs/adr/0085 E9).
 */
public record UpdateCareLogRequest(
        @Schema(description = "Ăn", example = "Ăn hết 1 bát hạt")
        @Size(max = 300, message = "Mục ăn tối đa 300 ký tự")
        String eating,

        @Schema(description = "Uống", example = "Uống đủ nước")
        @Size(max = 300, message = "Mục uống tối đa 300 ký tự")
        String drinking,

        @Schema(description = "Vệ sinh", example = "Đi vệ sinh bình thường")
        @Size(max = 300, message = "Mục vệ sinh tối đa 300 ký tự")
        String hygiene,

        @Schema(description = "Hoạt động", example = "Chạy chơi 30 phút")
        @Size(max = 300, message = "Mục hoạt động tối đa 300 ký tự")
        String activity,

        @Schema(description = "Ghi chú", example = "Hơi lười ăn buổi tối")
        @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
        String note,

        @Schema(description = "URL ảnh thay toàn bộ danh sách cũ (http / https), tối đa 5 [CFG]")
        List<String> photoUrls) {
}
