package com.petcare.module.boarding.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mục nhật ký chăm sóc (boarding-v1 {@code CareLog}). */
public record CareLogResponse(
        @Schema(description = "Mã mục nhật ký", example = "31")
        Long careLogId,

        @Schema(description = "Đặt chỗ lưu trú", example = "88")
        Long bookingId,

        @Schema(description = "Ngày của mục nhật ký", example = "2026-11-03")
        LocalDate logDate,

        @Schema(description = "Ăn", example = "Ăn hết 1 bát hạt")
        String eating,

        @Schema(description = "Uống", example = "Uống đủ nước")
        String drinking,

        @Schema(description = "Vệ sinh", example = "Đi vệ sinh bình thường")
        String hygiene,

        @Schema(description = "Hoạt động", example = "Chạy chơi 30 phút")
        String activity,

        @Schema(description = "Ghi chú", example = "Hơi lười ăn buổi tối")
        String note,

        @Schema(description = "URL ảnh")
        List<String> photoUrls,

        @Schema(description = "Đánh dấu bất thường (BR-LT-12)", example = "false")
        boolean isAbnormal,

        @Schema(description = "Tài khoản nhân viên ghi", example = "12")
        Long recordedBy,

        @Schema(description = "Tên nhân viên ghi", example = "Nguyễn Văn A")
        String recordedByName,

        @Schema(description = "Thời điểm ghi", example = "2026-11-03T09:00:00Z")
        Instant createdAt,

        @Schema(description = "Người ghi sửa được tới thời điểm này: createdAt + 1 giờ [CFG]",
                example = "2026-11-03T10:00:00Z")
        Instant editableUntil,

        @Schema(description = "Các bản bổ sung, theo thứ tự thêm")
        List<CareLogAddendumResponse> addenda) {
}
