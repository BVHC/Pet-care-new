package com.petcare.module.workforce.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Đăng ký ca theo tuần (workforce-v1 {@code ShiftRegistration}, #17 và về sau #15). Đúng 5 trường theo thứ tự hợp đồng;
 * {@code assignments} không bao giờ {@code null} (tuần chưa có phân ca là mảng rỗng). Phần tử dùng lại
 * {@link ShiftAssignmentResponse} (docs/adr/0138, mục "Đăng ký tuần — đọc #17").
 */
public record ShiftRegistrationResponse(
        @Schema(description = "Thứ Hai đầu tuần, yyyy-MM-dd", example = "2026-11-23", type = "string", format = "date")
        LocalDate weekStart,

        @Schema(description = "Định mức ca mỗi tuần, [CFG] workforce.weekly_required_shifts (BR-NS-12)", example = "10")
        int requiredShifts,

        @Schema(description = "Số phân ca SCHEDULED của tuần, cả SELF và MANAGER; luôn bằng số phần tử của assignments",
                example = "8")
        int shiftCount,

        @Schema(description = "Hạn sửa đăng ký tuần: 00:00 thứ Hai của weekStart theo giờ Việt Nam, trả dạng UTC "
                + "(BR-NS-11); GET không kiểm hạn, chỉ báo để client hiển thị",
                example = "2026-11-22T17:00:00Z")
        Instant editableUntil,

        @Schema(description = "Phân ca SCHEDULED của nhân viên trong tuần (SELF và MANAGER, phân biệt bằng source), "
                + "sắp theo ngày rồi giờ bắt đầu; tuần chưa có phân ca là mảng rỗng")
        List<ShiftAssignmentResponse> assignments) {
}
