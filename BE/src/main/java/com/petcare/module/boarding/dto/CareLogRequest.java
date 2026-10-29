package com.petcare.module.boarding.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Ghi nhật ký chăm sóc (boarding-v1 {@code CareLogRequest}, #20). */
public record CareLogRequest(
        @Schema(description = "Ngày của mục nhật ký; mặc định hôm nay, trong khoảng ngày nhận thực tế tới hôm nay",
                example = "2026-11-03")
        LocalDate logDate,

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

        @Schema(description = "URL ảnh (http / https), tối đa 5 [CFG] (A5)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng gửi danh sách ảnh (có thể rỗng)")
        List<String> photoUrls,

        @Schema(description = "true → thông báo ngay khách và lễ tân (BR-LT-12)", example = "false")
        Boolean isAbnormal) {
}
